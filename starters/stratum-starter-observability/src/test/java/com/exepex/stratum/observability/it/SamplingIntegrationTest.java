package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.observability.testsupport.RecordedSpans;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(
    classes = {ReactiveOrdersApplication.class, RecordedSpans.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      // Stratum's startup check needs an address before @DynamicPropertySource runs; the
      // collector stub's address then replaces this one.
      "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
      "management.otlp.metrics.export.enabled=false"
    })
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

  /** Each way a caller can ask for its trace to be kept. */
  private static final List<CallerSamplingHeader> SAMPLED_HEADERS =
      List.of(CallerSamplingHeader.values());

  /** Where the nested groups find {@link #SAMPLED_HEADERS}. */
  private static final String SAMPLED_HEADERS_SOURCE =
      "com.exepex.stratum.observability.it.SamplingIntegrationTest#SAMPLED_HEADERS";

  /** How long a test waits for a request's server span to end. */
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

  /**
   * A running service that a caller sends orders to, and the spans that ended in it. Each nested
   * group below extends it, with its own sampler.
   */
  // Closes each group's application after its tests, so its tracing never reaches the next.
  @DirtiesContext
  class RunningService {

    /** The port the service listens on. */
    @LocalServerPort private int port;

    /** Every span that ended in the service. */
    @Autowired private RecordedSpans recordedSpans;

    /** Sends orders to the service; made once the port is known. */
    private ReactiveOrdersCaller caller;

    /** Points the caller at the service and forgets the spans of earlier tests. */
    @BeforeEach
    void callTheService() {
      caller = new ReactiveOrdersCaller(port);
      recordedSpans.clear();
    }

    /**
     * Sends one order in the given trace, with this form's request to keep it.
     *
     * @param header the form that asks for the trace to be kept
     * @param traceId the caller's trace ID
     * @throws IOException if the request cannot be sent
     * @throws InterruptedException if the thread is interrupted while waiting for the answer
     */
    void sendOrder(CallerSamplingHeader header, String traceId)
        throws IOException, InterruptedException {
      var request = caller.orderRequest(ORDER_NUMBER);
      header.headers(traceId).forEach(request::header);
      caller.send(request.build(), ORDER_NUMBER);
    }

    /**
     * Waits until the service's span for the request in this trace has ended.
     *
     * @param traceId the trace ID
     * @return the trace's ended spans, oldest first
     */
    List<SpanData> awaitEndedServerSpan(String traceId) {
      await().atMost(SPAN_END_TIMEOUT).until(() -> hasEndedServerSpan(traceId));
      return endedSpans(traceId);
    }

    /**
     * Returns the spans that have ended so far in this trace.
     *
     * @param traceId the trace ID
     * @return the ended spans, oldest first
     */
    List<SpanData> endedSpans(String traceId) {
      return recordedSpans.endedWithTraceId(traceId);
    }

    /** Returns whether the service's span for the request in this trace has ended. */
    private boolean hasEndedServerSpan(String traceId) {
      return recordedSpans.endedWithTraceId(traceId).stream()
          .anyMatch(span -> span.getKind() == SpanKind.SERVER);
    }
  }

  @Nested
  @DisplayName("with Stratum's default sampler")
  class WithStratumSampler extends RunningService {

    @ParameterizedTest(name = "{0}")
    @FieldSource(SAMPLED_HEADERS_SOURCE)
    @DisplayName("only the trace ID below the bound is kept, whatever the caller's headers say")
    void keepsOnlyTraceIdsBelowTheBound(CallerSamplingHeader header)
        throws IOException, InterruptedException {
      var droppedTraceId = header.traceId(DROPPED_RANDOM_PART);
      var keptTraceId = header.traceId(KEPT_RANDOM_PART);

      sendOrder(header, droppedTraceId);
      sendOrder(header, keptTraceId);

      awaitEndedServerSpan(keptTraceId);
      // A dropped span never reaches a span processor, so none can arrive later.
      assertThat(endedSpans(droppedTraceId)).isEmpty();
    }
  }

  @Nested
  @TestPropertySource(
      properties = "management.opentelemetry.tracing.sampler=parent-based-trace-id-ratio")
  @DisplayName("with Spring Boot's sampler, which follows the caller")
  class WithCallerFollowingSampler extends RunningService {

    @ParameterizedTest(name = "{0}")
    @FieldSource(SAMPLED_HEADERS_SOURCE)
    @DisplayName("each header form asks for its trace to be kept, even at the bound")
    void eachHeaderFormAsksForSampling(CallerSamplingHeader header)
        throws IOException, InterruptedException {
      var traceIdAtTheBound = header.traceId(DROPPED_RANDOM_PART);

      sendOrder(header, traceIdAtTheBound);

      assertThat(awaitEndedServerSpan(traceIdAtTheBound))
          .extracting(SpanData::getKind)
          .contains(SpanKind.SERVER);
    }
  }
}
