package com.exepex.stratum.restclient.it;

import static com.exepex.stratum.restclient.testsupport.calls.CallLines.assertCallLine;
import static com.exepex.stratum.restclient.testsupport.calls.CallLines.linesOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.StratumHttpClients;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls;
import com.exepex.stratum.restclient.testsupport.QueuedDownstream;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.calls.AuditedCalls;
import com.exepex.stratum.restclient.testsupport.calls.CallsApplication;
import com.exepex.stratum.restclient.testsupport.calls.CallsTestData;
import com.exepex.stratum.restclient.testsupport.calls.OrdersCalls;
import com.exepex.stratum.restclient.testsupport.calls.SilentCalls;
import java.util.function.Supplier;
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
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@SpringBootTest(classes = CallsApplication.class)
@TestPropertySource(locations = CallsTestData.SETTINGS)
@ExtendWith(OutputCaptureExtension.class)
@Timeout(30)
@DisplayName("Without the logging starter's redaction, a call writes one line with its template")
class CallLogIntegrationTest {

  /** The attempts of a call that fails each time. */
  private static final String ALL_ATTEMPTS = "attempts=" + (CallsTestData.RETRIES + 1);

  /** The other service, which answers each request with the next queued status. */
  @AutoClose private static final QueuedDownstream DOWNSTREAM = QueuedDownstream.start();

  @Autowired private OrdersCalls orders;

  @Autowired private AuditedCalls audited;

  @Autowired private SilentCalls silent;

  @Autowired private StratumHttpClients clients;

  @Autowired private ApplicationContext beans;

  /**
   * Points the clients at the other service, and the ones these tests do not call at a closed port.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    Supplier<Object> unused = () -> RestClientTestData.UNUSED_URL;
    CallsTestData.addresses(registry, DOWNSTREAM::url, unused, unused);
  }

  @Nested
  @DisplayName("a failed call")
  class Failed {

    @Test
    @DisplayName("503 four times writes exactly one WARN, after the retries")
    void oneLinePerFailedCall(CapturedOutput output) {
      CallsTestData.failEveryAttempt(DOWNSTREAM);

      assertThatExceptionOfType(RestClientException.class)
          .isThrownBy(() -> orders.find(CallsTestData.ORDER_ID, CallsTestData.STATUS));

      assertCallLine(
          output,
          CallsTestData.ORDERS,
          "WARN",
          "HTTP client call failed: client=orders method=GET uri=/orders/{id} status=503",
          "outcome=SERVER_ERROR",
          ALL_ATTEMPTS,
          "durationMs=");
    }

    @Test
    @DisplayName("a 404 that a method returning Optional reads as empty writes nothing")
    void optionalNotFoundSilent(CapturedOutput output) {
      DOWNSTREAM.answerNext(CallsTestData.NOT_FOUND);

      assertThat(orders.findIfPresent(CallsTestData.ORDER_ID)).isEmpty();

      assertThat(linesOf(output, CallsTestData.ORDERS)).isEmpty();
    }

    @Test
    @DisplayName("a 404 on any other method writes one WARN, as a client error")
    void plainNotFoundWarns(CapturedOutput output) {
      DOWNSTREAM.answerNext(CallsTestData.NOT_FOUND);

      assertThatExceptionOfType(RestClientException.class)
          .isThrownBy(() -> orders.find(CallsTestData.ORDER_ID, CallsTestData.STATUS));

      assertCallLine(output, CallsTestData.ORDERS, "status=404", "outcome=CLIENT_ERROR");
    }

    @Test
    @DisplayName("the line holds no exception message, body, header or status text")
    void noSecretsInLine(CapturedOutput output) {
      CallsTestData.failEveryAttempt(DOWNSTREAM);

      assertThatExceptionOfType(RestClientException.class)
          .isThrownBy(() -> orders.find(CallsTestData.ORDER_ID, CallsTestData.STATUS));

      assertThat(assertCallLine(output, CallsTestData.ORDERS))
          .doesNotContain(QueuedDownstream.ERROR_BODY)
          .doesNotContain(QueuedDownstream.ANSWER_NUMBER)
          .doesNotContain("Service Unavailable")
          .doesNotContain("Exception");
    }
  }

  @Nested
  @DisplayName("without the redaction")
  class WithoutRedaction {

    @Test
    @DisplayName("the line holds the template only: no url, host, path or query value")
    void templateOnly(CapturedOutput output) {
      CallsTestData.failEveryAttempt(DOWNSTREAM);

      assertThatExceptionOfType(RestClientException.class)
          .isThrownBy(() -> orders.find(CallsTestData.ORDER_ID, CallsTestData.STATUS));

      assertThat(assertCallLine(output, CallsTestData.ORDERS))
          .doesNotContain("url=")
          .doesNotContain("127.0.0.1")
          .doesNotContain(CallsTestData.ORDER_ID)
          .doesNotContain(CallsTestData.STATUS);
    }

    @Test
    @DisplayName(
        "a query joined into a team's own call is cut at the ?, so its secret is not written")
    void queryNotWritten(CapturedOutput output) {
      CallsTestData.failEveryAttempt(DOWNSTREAM);
      var orderClient = clients.restClient(CallsTestData.ORDERS);

      assertThatExceptionOfType(RestClientException.class)
          .isThrownBy(
              () ->
                  orderClient
                      .get()
                      .uri("/orders?api_key=" + CallsTestData.API_KEY)
                      .retrieve()
                      .toBodilessEntity());

      assertThat(assertCallLine(output, CallsTestData.ORDERS, "uri=/orders status=503"))
          .doesNotContain(CallsTestData.API_KEY);
    }

    @Test
    @DisplayName("a 100 KB template of a team's own call is written cut at 256 characters")
    void uriCut(CapturedOutput output) {
      CallsTestData.failEveryAttempt(DOWNSTREAM);
      var longPath = "/" + "a".repeat(CallsTestData.HOSTILE_LENGTH);
      var orderClient = clients.restClient(CallsTestData.ORDERS);

      assertThatExceptionOfType(RestClientException.class)
          .isThrownBy(() -> orderClient.get().uri(longPath).retrieve().toBodilessEntity());

      var written = longPath.substring(0, HttpClientConstants.MAX_LOGGED_URI_LENGTH);
      assertThat(assertCallLine(output, CallsTestData.ORDERS, "uri=" + written + " "))
          .doesNotContain(written + "a");
    }
  }

  @Nested
  @DisplayName("a successful call")
  class Success {

    @Test
    @DisplayName("with call-log errors, the default, writes nothing")
    void silentAtErrors(CapturedOutput output) {
      DOWNSTREAM.answerNext();

      orders.find(CallsTestData.ORDER_ID, CallsTestData.STATUS);

      assertThat(linesOf(output, CallsTestData.ORDERS)).isEmpty();
    }
  }

  @Nested
  @DisplayName("by call-log")
  class Levels {

    @Test
    @DisplayName("with all, a success writes INFO without a status, and a failure WARN")
    void allWritesSuccess(CapturedOutput output) {
      DOWNSTREAM.answerNext();
      audited.find(CallsTestData.ORDER_ID);

      assertThat(assertCallLine(output, CallsTestData.AUDITED, "INFO", "outcome=SUCCESS"))
          .contains("HTTP client call: client=audited")
          .doesNotContain("status=");

      DOWNSTREAM.answerNext(CallsTestData.BAD_REQUEST);
      assertThatExceptionOfType(RestClientResponseException.class)
          .isThrownBy(() -> audited.find(CallsTestData.ORDER_ID));
      assertThat(linesOf(output, CallsTestData.AUDITED).getLast())
          .contains("WARN", "status=400", "outcome=CLIENT_ERROR");
    }

    @Test
    @DisplayName("with none, a failed call writes no line")
    void noneSilent(CapturedOutput output) {
      CallsTestData.failEveryAttempt(DOWNSTREAM);

      assertThatExceptionOfType(RestClientException.class).isThrownBy(silent::ping);

      assertThat(linesOf(output, CallsTestData.SILENT)).isEmpty();
    }
  }

  @Nested
  @DisplayName("through a client's other ways of calling")
  class OtherPaths {

    @Test
    @DisplayName("restClient(name) writes one line with the call's template")
    void restClientOfName(CapturedOutput output) {
      CallsTestData.failEveryAttempt(DOWNSTREAM);
      var orderClient = clients.restClient(CallsTestData.ORDERS);

      assertThatExceptionOfType(RestClientException.class)
          .isThrownBy(
              () ->
                  orderClient
                      .get()
                      .uri(CallsTestData.ORDER_TEMPLATE, CallsTestData.ORDER_ID)
                      .retrieve()
                      .toBodilessEntity());

      assertCallLine(output, CallsTestData.ORDERS, "uri=/orders/{id} status=503", ALL_ATTEMPTS);
    }

    @Test
    @DisplayName("the RestClient bean writes one line with the call's template")
    void restClientBean(CapturedOutput output) {
      CallsTestData.failEveryAttempt(DOWNSTREAM);

      assertThatExceptionOfType(RestClientException.class)
          .isThrownBy(
              () ->
                  ClientBeanCalls.get(
                      beans,
                      ClientBeanCalls.ClientBean.REST_CLIENT,
                      CallsTestData.ORDERS,
                      ScriptedDownstream.STOCK));

      assertCallLine(output, CallsTestData.ORDERS, "uri=/stock status=503", ALL_ATTEMPTS);
    }

    @Test
    @DisplayName("the RestTemplate bean writes one line, with uri=none as it has no template")
    void restTemplateBean(CapturedOutput output) {
      CallsTestData.failEveryAttempt(DOWNSTREAM);

      assertThatExceptionOfType(RestClientException.class)
          .isThrownBy(
              () ->
                  ClientBeanCalls.get(
                      beans,
                      ClientBeanCalls.ClientBean.REST_TEMPLATE,
                      CallsTestData.ORDERS,
                      ScriptedDownstream.STOCK));

      assertCallLine(output, CallsTestData.ORDERS, "uri=none status=503", ALL_ATTEMPTS);
    }
  }
}
