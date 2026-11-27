package com.exepex.stratum.resilience.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.resilience.ResilienceConstants;
import com.exepex.stratum.resilience.testsupport.CapturedLines;
import com.exepex.stratum.resilience.testsupport.HeldRequests;
import com.exepex.stratum.resilience.testsupport.RefusalAssertions;
import com.exepex.stratum.resilience.testsupport.RouteMeterReader;
import com.exepex.stratum.resilience.testsupport.RouteRequests;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

@SpringBootTest(
    classes = LimitedApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ResilienceConstants.INCOMING_ROUTES + "[/limited/**].max-concurrent=5")
@DisplayName("A route limited to 5 runs at most 5 requests at once, whatever the handler returns")
class RouteLimitIntegrationTest {

  /** Each way a Spring MVC handler answers, synchronous and asynchronous, one path each. */
  private static final List<String> HANDLER_TYPES =
      List.of(
          "sync",
          "callable",
          "web-async-task",
          "deferred-result",
          "completable-future",
          "sse-emitter",
          "response-body-emitter",
          "streaming",
          "chained");

  /** The route's limit. */
  private static final int LIMIT = 5;

  /** The requests sent at once, far over the limit. */
  private static final int REQUESTS = 30;

  /** The log lines of the error response factory, which must write none for a refusal. */
  @RegisterExtension
  private final CapturedLines factoryLines = CapturedLines.ofErrorResponseFactory();

  /** The log lines of the resilience module, which must write none for a refusal. */
  @RegisterExtension
  private final CapturedLines resilienceLines =
      new CapturedLines(ResilienceConstants.class.getPackageName());

  @LocalServerPort private int port;

  @Autowired private HeldRequests held;

  @Autowired private MeterRegistry meterRegistry;

  @ParameterizedTest(name = "{0}")
  @FieldSource("HANDLER_TYPES")
  @DisplayName(
      "30 at once: 5 run, 25 get 503 with SX-RESILIENCE-0001 and Retry-After, no log line, every"
          + " permit comes back")
  void holdsTheLimit(String handlerType) {
    var meters = new RouteMeterReader(meterRegistry, "/limited/**");
    final var refusedBefore = meters.refused();
    held.close();

    var answers = RouteRequests.sendAtOnce(port, "/limited/" + handlerType, REQUESTS);
    await()
        .atMost(RouteRequests.WAIT)
        .until(() -> held.running() == LIMIT && doneCount(answers) == REQUESTS - LIMIT);
    held.open();
    var responses = answers.stream().map(CompletableFuture::join).toList();

    assertThat(responses)
        .filteredOn(response -> response.statusCode() == HttpStatus.OK.value())
        .hasSize(LIMIT);
    assertThat(responses)
        .filteredOn(response -> response.statusCode() != HttpStatus.OK.value())
        .hasSize(REQUESTS - LIMIT)
        .allSatisfy(
            response ->
                RefusalAssertions.checkRefused(
                    response, HttpStatus.SERVICE_UNAVAILABLE.value(), "1"));
    assertThat(held.mostRunning()).isEqualTo(LIMIT);
    assertThat(meters.refused() - refusedBefore).isEqualTo(REQUESTS - LIMIT);
    await().atMost(RouteRequests.WAIT).until(() -> meters.active() == 0);
    assertThat(factoryLines.lines()).isEmpty();
    assertThat(resilienceLines.lines()).isEmpty();
  }

  /** Returns how many of the answers have arrived. */
  private long doneCount(List<CompletableFuture<HttpResponse<String>>> answers) {
    return answers.stream().filter(CompletableFuture::isDone).count();
  }
}
