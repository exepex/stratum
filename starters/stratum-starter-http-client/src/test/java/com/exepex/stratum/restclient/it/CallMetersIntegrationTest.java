package com.exepex.stratum.restclient.it;

import static com.exepex.stratum.restclient.testsupport.calls.CallLines.assertCallLine;
import static com.exepex.stratum.restclient.testsupport.calls.CallLines.linesOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.CallOutcome;
import com.exepex.stratum.restclient.testsupport.QueuedDownstream;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.calls.CallsApplication;
import com.exepex.stratum.restclient.testsupport.calls.CallsTestData;
import com.exepex.stratum.restclient.testsupport.calls.FragileCalls;
import com.exepex.stratum.restclient.testsupport.calls.OrdersCalls;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.net.URI;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClientException;

@SpringBootTest(classes = CallsApplication.class)
@TestPropertySource(locations = CallsTestData.SETTINGS)
// Each test starts with a closed breaker and empty meters.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ExtendWith(OutputCaptureExtension.class)
@Timeout(30)
@DisplayName("Each call is timed by outcome, its retries counted, and the breaker's state shown")
class CallMetersIntegrationTest {

  /** The other service, which answers each request with the next queued status. */
  @AutoClose private static final QueuedDownstream DOWNSTREAM = QueuedDownstream.start();

  @Autowired private OrdersCalls orders;

  @Autowired private FragileCalls fragile;

  @Autowired private MeterRegistry registry;

  /**
   * Points the clients at the other service, and the held client at a closed port.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    CallsTestData.addresses(
        registry, DOWNSTREAM::url, () -> RestClientTestData.UNUSED_URL, DOWNSTREAM::url);
  }

  /** Returns how many calls of a client ended with the outcome. */
  private long calls(String client, CallOutcome outcome) {
    var timer =
        registry
            .find(HttpClientConstants.CALLS_METER)
            .tags(HttpClientConstants.CLIENT_TAG, client, "outcome", outcome.name())
            .timer();
    return timer == null ? 0 : timer.count();
  }

  /** Returns the fragile client's breaker state gauge for one state. */
  private double breakerState(String state) {
    return registry
        .get(HttpClientConstants.BREAKER_STATE_METER)
        .tags(HttpClientConstants.CLIENT_TAG, CallsTestData.FRAGILE, "state", state)
        .gauge()
        .value();
  }

  /** Makes a call of the fragile client whose attempts get 503 until its breaker opens. */
  private void openBreaker() {
    CallsTestData.failEveryAttempt(DOWNSTREAM);
    assertThatExceptionOfType(StratumException.class).isThrownBy(fragile::get);
  }

  @Nested
  @DisplayName("the calls timer and the retries counter")
  class Calls {

    @Test
    @DisplayName("503, 503, 200 counts two retries and one successful call")
    void retriesCounted() {
      DOWNSTREAM.answerNext(CallsTestData.UNAVAILABLE, CallsTestData.UNAVAILABLE);

      orders.find(CallsTestData.ORDER_ID, CallsTestData.STATUS);

      assertThat(calls(CallsTestData.ORDERS, CallOutcome.SUCCESS)).isOne();
      assertThat(
              registry
                  .get(HttpClientConstants.RETRIES_METER)
                  .tag(HttpClientConstants.CLIENT_TAG, CallsTestData.ORDERS)
                  .counter()
                  .count())
          .isEqualTo(2);
    }

    @Test
    @DisplayName("a 404 that a method returning Optional reads as empty counts as a success")
    void optionalNotFoundIsSuccess() {
      DOWNSTREAM.answerNext(CallsTestData.NOT_FOUND);

      assertThat(orders.findIfPresent(CallsTestData.ORDER_ID)).isEmpty();

      assertThat(calls(CallsTestData.ORDERS, CallOutcome.SUCCESS)).isOne();
      assertThat(calls(CallsTestData.ORDERS, CallOutcome.CLIENT_ERROR)).isZero();
    }
  }

  @Nested
  @DisplayName("the circuit breaker")
  class Breaker {

    @Test
    @DisplayName("a breaker that opens mid-call leaves the last answer's outcome, not refused")
    void refusedMidRetry(CapturedOutput output) {
      openBreaker();

      assertCallLine(
          output, CallsTestData.FRAGILE, "status=503", "outcome=SERVER_ERROR", "attempts=2");
      assertThat(calls(CallsTestData.FRAGILE, CallOutcome.SERVER_ERROR)).isOne();
      assertThat(calls(CallsTestData.FRAGILE, CallOutcome.REFUSED)).isZero();
    }

    @Test
    @DisplayName("each call the open breaker refuses is counted as refused and writes no line")
    void refusedCountedNotLogged(CapturedOutput output) {
      openBreaker();

      assertThatExceptionOfType(StratumException.class).isThrownBy(fragile::get);
      assertThatExceptionOfType(StratumException.class).isThrownBy(fragile::get);

      assertThat(calls(CallsTestData.FRAGILE, CallOutcome.REFUSED)).isEqualTo(2);
      assertThat(linesOf(output, CallsTestData.FRAGILE)).hasSize(1);
    }

    @Test
    @DisplayName("the breaker state gauge shows closed, then open once the breaker opens")
    void breakerStateGauge() {
      DOWNSTREAM.answerNext();
      fragile.get();
      assertThat(breakerState("closed")).isOne();
      assertThat(breakerState("open")).isZero();

      openBreaker();

      assertThat(breakerState("open")).isOne();
      assertThat(breakerState("closed")).isZero();
      assertThat(breakerState("half_open")).isZero();
    }
  }

  @Nested
  @DisplayName("the tags")
  class Tags {

    @Test
    @DisplayName("no tag of a client's meters holds a path value, a query value or the host")
    void tagsHaveTemplateOnly() {
      CallsTestData.failEveryAttempt(DOWNSTREAM);
      assertThatExceptionOfType(RestClientException.class)
          .isThrownBy(() -> orders.find(CallsTestData.ORDER_ID, CallsTestData.STATUS));

      var tagValues =
          registry.getMeters().stream()
              .filter(meter -> meter.getId().getName().startsWith("stratum.http.client"))
              .flatMap(meter -> meter.getId().getTags().stream())
              .map(Tag::getValue)
              .toList();
      assertThat(tagValues)
          .isNotEmpty()
          .noneMatch(value -> value.contains(CallsTestData.ORDER_ID))
          .noneMatch(value -> value.contains(CallsTestData.STATUS))
          .noneMatch(value -> value.contains(URI.create(DOWNSTREAM.url()).getHost()));
    }
  }
}
