package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.observability.testsupport.RecordedSpans;
import io.opentelemetry.api.trace.SpanKind;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = {ReactiveOrdersApplication.class, RecordedSpans.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      // Stratum's startup check needs an address before @DynamicPropertySource runs; the
      // collector stub's address then replaces this one.
      "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
      "management.otlp.metrics.export.enabled=false"
    })
// Closes the application after these tests, so its tracing and threads never reach the next.
@DirtiesContext
@DisplayName("A caller's sampled flag cannot make a service keep a trace its trace ID does not")
class SamplingIntegrationTest {

  /*
   * Spring Boot's default probability is the float 0.10f, and OpenTelemetry keeps a trace when the
   * absolute value of its last 16 hex digits is below (long) (0.10f * Long.MAX_VALUE), which is
   * 0x0cccccd000000000. The double 0.10 would give 0x0ccccccccccccd00 instead, so these digits sit
   * on each side of the float's bound, as the service really runs.
   */

  /** The last 16 hex digits of a trace ID just below Spring Boot's default bound: kept. */
  private static final String KEPT_RANDOM_PART = "0ccccccfffffffff";

  /** The last 16 hex digits of a trace ID at Spring Boot's default bound: dropped. */
  private static final String DROPPED_RANDOM_PART = "0cccccd000000000";

  /** Each way a caller can ask for its trace to be kept, sent with both trace IDs. */
  private static final List<CallerSamplingHeader> SAMPLED_HEADERS =
      List.of(CallerSamplingHeader.values());

  /** How long the test waits for the kept request's server span to end. */
  private static final Duration SPAN_END_TIMEOUT = Duration.ofSeconds(5);

  /** The downstream path that the reactive handler calls for each order. */
  private static final String DOWNSTREAM_PATH = "/downstream/sampling";

  /** The order number sent on every request. */
  private static final String ORDER_NUMBER = "ORD-8001";

  /**
   * Stands in for the downstream service that the handler calls on every request, and takes the
   * spans, since Spring Boot propagates trace headers only while span export is on.
   */
  @AutoClose private static final CollectorStub DOWNSTREAM = new CollectorStub();

  /** The port the service listens on. */
  @LocalServerPort private int port;

  /** Every span that ended in the service. */
  @Autowired private RecordedSpans recordedSpans;

  /** Sends requests to the service, as its caller. */
  private final HttpClient caller = HttpClient.newHttpClient();

  /**
   * Points the reactive handler's WebClient and the span export at the stub.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void callDownstreamStub(DynamicPropertyRegistry registry) {
    registry.add("orders.downstream-url", () -> DOWNSTREAM.url(DOWNSTREAM_PATH));
    DOWNSTREAM.receiveSpans(registry);
  }

  @ParameterizedTest(name = "{0}")
  @FieldSource("SAMPLED_HEADERS")
  @DisplayName("only the trace ID below the bound is kept, whatever the caller's headers say")
  void keepsOnlyTraceIdsBelowTheBound(CallerSamplingHeader header)
      throws IOException, InterruptedException {
    var keptTraceId = header.traceId(KEPT_RANDOM_PART);
    var droppedTraceId = header.traceId(DROPPED_RANDOM_PART);
    recordedSpans.clear();

    send(header.headers(droppedTraceId));
    send(header.headers(keptTraceId));

    await().atMost(SPAN_END_TIMEOUT).until(() -> hasEndedServerSpan(keptTraceId));
    // A dropped span never reaches a span processor, so none can arrive later.
    assertThat(recordedSpans.endedWithTraceId(droppedTraceId)).isEmpty();
  }

  /** Sends one order request with the caller's headers and checks that it succeeded. */
  private void send(Map<String, String> callerHeaders) throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder(
            URI.create(
                "http://localhost:"
                    + port
                    + ReactiveOrdersApplication.PLACE_ORDER_PATH
                    + "?"
                    + ReactiveOrdersApplication.ORDER_NUMBER_PARAMETER
                    + "="
                    + ORDER_NUMBER));
    callerHeaders.forEach(request::header);
    var response = caller.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.body()).isEqualTo(ORDER_NUMBER);
  }

  /** Returns whether the service's span for the request in this trace has ended. */
  private boolean hasEndedServerSpan(String traceId) {
    return recordedSpans.endedWithTraceId(traceId).stream()
        .anyMatch(span -> span.getKind() == SpanKind.SERVER);
  }
}
