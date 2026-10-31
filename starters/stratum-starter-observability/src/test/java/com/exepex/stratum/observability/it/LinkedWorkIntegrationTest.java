package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.observability.linkedwork.LinkedWork;
import com.exepex.stratum.observability.linkedwork.ReactiveLinkedWork;
import com.exepex.stratum.observability.linkedwork.WorkOrigin;
import com.exepex.stratum.observability.testsupport.RecordedSpans;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@SpringBootTest(
    classes = {OrdersApplication.class, RecordedSpans.class},
    properties = {
      "spring.main.web-application-type=none",
      "stratum.observability.enabled=true",
      "management.tracing.sampling.probability=1.0",
      "management.tracing.export.enabled=false",
      "management.otlp.metrics.export.enabled=false"
    })
// Closes the application after this class, so its tracing and threads never reach the next one.
@DirtiesContext
@DisplayName("Linked work runs in its own trace, linked back to the requests that caused it")
class LinkedWorkIntegrationTest {

  /** The correlation ID of the request that saved the work. */
  private static final String ORDER_CORRELATION_ID = "order-42-c7d1";

  /** The fixed name of the work. */
  private static final String INVOICE_EXPORT = "invoice-export";

  /** The fixed name of the reactive work. */
  private static final String RECEIPT_EXPORT = "receipt-export";

  /** How long reactive work may take. */
  private static final Duration REACTIVE_TIMEOUT = Duration.ofSeconds(10);

  /** How often the endless receipt Flux emits, until it is cancelled. */
  private static final Duration RECEIPT_INTERVAL = Duration.ofMillis(10);

  /** Starts and ends spans. */
  @Autowired private Tracer tracer;

  /** Starts observations, which Spring turns into spans. */
  @Autowired private ObservationRegistry observationRegistry;

  /** Runs work linked to the requests that caused it. */
  @Autowired private LinkedWork linkedWork;

  /** Runs a Mono or Flux linked to the requests that caused it. */
  @Autowired private ReactiveLinkedWork reactiveLinkedWork;

  /** Every span that ended. */
  @Autowired private RecordedSpans recordedSpans;

  /** Forgets the spans of earlier tests, so each test reads only its own. */
  @BeforeEach
  void forgetEarlierSpans() {
    recordedSpans.clear();
  }

  /** Clears the logging context, so no test sees fields left by another. */
  @AfterEach
  void clearLoggingContext() {
    MDC.clear();
  }

  /**
   * Returns the origin that a request with this correlation ID stores with its work, read back from
   * its stored text as a later job would.
   */
  private WorkOrigin originOfRequest(String correlationId) {
    var span = tracer.nextSpan().name("place-order").start();
    try (var _ = tracer.withSpan(span);
        var _ =
            tracer.createBaggageInScope(LoggingContextConstants.CORRELATION_ID, correlationId)) {
      var storedOrigin = linkedWork.captureOrigin().orElseThrow().toText();
      return WorkOrigin.fromText(storedOrigin);
    } finally {
      span.end();
    }
  }

  /** Returns the trace and correlation ID that the code running now sees. */
  private Map<String, String> idsSeenNow(Tracer tracer) {
    return Map.of(
        LoggingContextConstants.TRACE_ID,
        Objects.requireNonNull(tracer.currentSpan()).context().traceId(),
        LoggingContextConstants.CORRELATION_ID,
        Objects.requireNonNullElse(MDC.get(LoggingContextConstants.CORRELATION_ID), "none"));
  }

  /** Returns the ended span with this name. */
  private SpanData endedSpan(String name) {
    return recordedSpans.named(name);
  }

  /** Returns the trace and span ID that a span link points to. */
  private List<String> linkTarget(LinkData link) {
    SpanContext target = link.getSpanContext();
    return List.of(target.getTraceId(), target.getSpanId());
  }

  @Test
  @DisplayName(
      "work from one request gets a new trace linked to the request, and keeps its correlation ID")
  void linksOneOriginAndKeepsCorrelationId() {
    var origin = originOfRequest(ORDER_CORRELATION_ID);

    var idsInWork = linkedWork.call(INVOICE_EXPORT, List.of(origin), () -> idsSeenNow(tracer));

    assertThat(idsInWork.get(LoggingContextConstants.TRACE_ID)).isNotEqualTo(origin.traceId());
    assertThat(idsInWork.get(LoggingContextConstants.CORRELATION_ID))
        .isEqualTo(ORDER_CORRELATION_ID);
    var workSpan = endedSpan(INVOICE_EXPORT);
    assertThat(workSpan.getParentSpanContext().isValid()).isFalse();
    assertThat(workSpan.getLinks())
        .extracting(this::linkTarget)
        .containsExactly(List.of(origin.traceId(), origin.spanId()));
  }

  @Test
  @DisplayName(
      "a batch for two requests links to both, inside the job's own span, with the job's own"
          + " correlation ID")
  void linksEveryOriginOfBatchInsideJobSpan() {
    var firstOrigin = originOfRequest(ORDER_CORRELATION_ID);
    var secondOrigin = originOfRequest("order-43-a9b2");
    var jobSpan = tracer.nextSpan().name("nightly-export").start();
    Map<String, String> idsInWork;
    try (var _ = tracer.withSpan(jobSpan)) {
      idsInWork =
          linkedWork.call(
              INVOICE_EXPORT, List.of(firstOrigin, secondOrigin), () -> idsSeenNow(tracer));
    } finally {
      jobSpan.end();
    }

    assertThat(idsInWork)
        .containsEntry(LoggingContextConstants.TRACE_ID, jobSpan.context().traceId())
        .containsEntry(LoggingContextConstants.CORRELATION_ID, jobSpan.context().traceId());
    var workSpan = endedSpan(INVOICE_EXPORT);
    assertThat(workSpan.getParentSpanId()).isEqualTo(jobSpan.context().spanId());
    assertThat(workSpan.getLinks())
        .extracting(this::linkTarget)
        .containsExactlyInAnyOrder(
            List.of(firstOrigin.traceId(), firstOrigin.spanId()),
            List.of(secondOrigin.traceId(), secondOrigin.spanId()));
  }

  @Test
  @DisplayName(
      "a Mono or Flux run as linked work keeps the new trace and the request's correlation ID"
          + " after a thread change")
  void reactiveWorkKeepsLinkedTraceAcrossThreads() {
    var origin = originOfRequest(ORDER_CORRELATION_ID);

    var jobObservation = Observation.start("nightly-export", observationRegistry);
    var idsInMono =
        reactiveLinkedWork
            .mono(
                INVOICE_EXPORT,
                List.of(origin),
                Mono.just("invoice")
                    .publishOn(Schedulers.boundedElastic())
                    .map(_ -> idsSeenNow(tracer)))
            .contextWrite(
                reactorContext ->
                    reactorContext.put(ObservationThreadLocalAccessor.KEY, jobObservation))
            .block(REACTIVE_TIMEOUT);
    jobObservation.stop();
    var idsInFlux =
        reactiveLinkedWork
            .flux(
                RECEIPT_EXPORT,
                List.of(origin),
                Flux.just("receipt")
                    .publishOn(Schedulers.boundedElastic())
                    .map(_ -> idsSeenNow(tracer)))
            .blockLast(REACTIVE_TIMEOUT);

    assertThat(idsInMono)
        .containsEntry(LoggingContextConstants.TRACE_ID, endedSpan(INVOICE_EXPORT).getTraceId())
        .containsEntry(LoggingContextConstants.CORRELATION_ID, ORDER_CORRELATION_ID);
    assertThat(idsInFlux)
        .containsEntry(LoggingContextConstants.TRACE_ID, endedSpan(RECEIPT_EXPORT).getTraceId())
        .containsEntry(LoggingContextConstants.CORRELATION_ID, ORDER_CORRELATION_ID);
    assertThat(endedSpan(INVOICE_EXPORT).getLinks()).hasSize(1);
    assertThat(endedSpan(INVOICE_EXPORT).getParentSpanId())
        .isEqualTo(endedSpan("nightly-export").getSpanId());
  }

  @Test
  @DisplayName(
      "a failing Mono records its failure on the span, a cancelled Flux still ends its span, and"
          + " the calling thread's trace and correlation ID are left as they were")
  void reactiveWorkEndsSpanOnFailureAndCancel() {
    var origin = originOfRequest(ORDER_CORRELATION_ID);
    var exportFailure = new IllegalStateException("export target refused the file");

    var failingWork =
        reactiveLinkedWork.mono(
            INVOICE_EXPORT,
            List.of(origin),
            Mono.error(exportFailure).publishOn(Schedulers.boundedElastic()));
    assertThatThrownBy(() -> failingWork.block(REACTIVE_TIMEOUT)).isSameAs(exportFailure);
    reactiveLinkedWork
        .flux(RECEIPT_EXPORT, List.of(origin), Flux.interval(RECEIPT_INTERVAL))
        .take(1)
        .blockLast(REACTIVE_TIMEOUT);

    var failedSpan = endedSpan(INVOICE_EXPORT);
    assertThat(failedSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(failedSpan.getEvents())
        .anySatisfy(event -> assertThat(event.getName()).isEqualTo("exception"));
    assertThat(endedSpan(RECEIPT_EXPORT).getLinks()).hasSize(1);
    assertThat(tracer.currentSpan()).isNull();
    assertThat(MDC.get(LoggingContextConstants.CORRELATION_ID)).isNull();
  }
}
