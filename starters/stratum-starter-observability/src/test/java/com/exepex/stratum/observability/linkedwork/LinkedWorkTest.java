package com.exepex.stratum.observability.linkedwork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.observability.ObservabilityConstants;
import com.exepex.stratum.observability.testsupport.RecordedSpans;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler.FirstMatchingCompositeObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@DisplayName(
    "LinkedWork captures where work comes from and runs it with its own span, without Spring or"
        + " Reactor's global hook")
class LinkedWorkTest {

  /** The fixed name of the work. */
  private static final String INVOICE_EXPORT = "invoice-export";

  /** The name of the scheduled job that runs the work. */
  private static final String NIGHTLY_EXPORT = "nightly-export";

  /** How long reactive work may take. */
  private static final Duration REACTIVE_TIMEOUT = Duration.ofSeconds(10);

  /** Keeps every span that ends. */
  private final RecordedSpans recordedSpans = new RecordedSpans();

  /** The SDK that makes the spans; closed after each test. */
  private final SdkTracerProvider tracerProvider =
      SdkTracerProvider.builder().addSpanProcessor(recordedSpans).build();

  /** Micrometer's view of the SDK, as Spring Boot builds it. */
  private final OtelTracer tracer = newTracer();

  /** Sends linked work to Stratum's handler and anything else to Micrometer's default one. */
  private final ObservationRegistry observationRegistry = newObservationRegistry();

  private final LinkedWork linkedWork = new LinkedWork(observationRegistry, tracer);

  /** Whether Reactor's JVM-wide context propagation was on before the test, to put it back. */
  private final boolean reactorPropagationWasOn = Hooks.isAutomaticContextPropagationEnabled();

  /** Closes the SDK and puts Reactor's JVM-wide context propagation back as it was. */
  @AfterEach
  void closeTracerProvider() {
    tracerProvider.close();
    if (reactorPropagationWasOn) {
      Hooks.enableAutomaticContextPropagation();
    }
  }

  /** Returns Micrometer's tracer over the SDK, with the correlation ID as baggage. */
  private OtelTracer newTracer() {
    var currentTraceContext = new OtelCurrentTraceContext();
    return new OtelTracer(
        tracerProvider.get("linked-work-test"),
        currentTraceContext,
        _ -> {},
        new OtelBaggageManager(
            currentTraceContext, List.of(LoggingContextConstants.CORRELATION_ID), List.of()));
  }

  /**
   * Returns a registry that traces linked work with Stratum's handler, as Spring Boot orders it.
   */
  private ObservationRegistry newObservationRegistry() {
    var registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(
            new FirstMatchingCompositeObservationHandler(
                new LinkedWorkTracingHandler(tracer),
                new DefaultTracingObservationHandler(tracer)));
    return registry;
  }

  /** Returns the origin captured inside a span that has this correlation ID in baggage. */
  private WorkOrigin originCapturedWith(String correlationId) {
    var span = tracer.nextSpan().name("place-order").start();
    try (var _ = tracer.withSpan(span);
        var _ =
            tracer.createBaggageInScope(LoggingContextConstants.CORRELATION_ID, correlationId)) {
      return linkedWork.captureOrigin().orElseThrow();
    } finally {
      span.end();
    }
  }

  @Test
  @DisplayName(
      "the span's parent is the parent observation even when no scope of it is open, as when"
          + " Reactor's context propagation is limited")
  void parentObservationIsParentWithoutOpenScope() {
    var jobObservation = Observation.start(NIGHTLY_EXPORT, observationRegistry);
    linkedWork
        .observation(INVOICE_EXPORT, List.of())
        .parentObservation(jobObservation)
        .start()
        .stop();
    jobObservation.stop();

    assertThat(recordedSpans.named(INVOICE_EXPORT).getParentSpanId())
        .isEqualTo(recordedSpans.named(NIGHTLY_EXPORT).getSpanId());
  }

  @Test
  @DisplayName(
      "a Mono's span is a child of the observation in the Reactor context, even with Reactor's"
          + " automatic context propagation off and a thread change")
  void reactiveParentComesFromReactorContext() {
    Hooks.disableAutomaticContextPropagation();
    var jobObservation = Observation.start(NIGHTLY_EXPORT, observationRegistry);
    new ReactiveLinkedWork(linkedWork)
        .mono(INVOICE_EXPORT, List.of(), Mono.just("invoice").publishOn(Schedulers.parallel()))
        .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, jobObservation))
        .block(REACTIVE_TIMEOUT);
    jobObservation.stop();

    assertThat(recordedSpans.named(INVOICE_EXPORT).getParentSpanId())
        .isEqualTo(recordedSpans.named(NIGHTLY_EXPORT).getSpanId());
  }

  @Test
  @DisplayName("work with no origin runs in its own trace, with no parent and no link")
  void runsWithoutOrigins() {
    linkedWork.run(INVOICE_EXPORT, List.of(), () -> {});

    var workSpan = recordedSpans.named(INVOICE_EXPORT);
    assertThat(workSpan.getLinks()).isEmpty();
    assertThat(workSpan.getParentSpanContext().isValid()).isFalse();
  }

  @Test
  @DisplayName("an unsafe correlation ID in incoming baggage is replaced by the trace ID")
  void unsafeBaggageFallsBackToTraceId() {
    var origin = originCapturedWith("order 42\nforged=line");

    assertThat(origin.correlationId()).isEqualTo(origin.traceId());
  }

  @Test
  @DisplayName("a request whose trace was not sampled is stored as not sampled")
  void unsampledRequestIsStoredAsNotSampled() {
    var unsampledCaller =
        tracer
            .traceContextBuilder()
            .traceId("4bf92f3577b34da6a3ce929d0e0e4736")
            .spanId("00f067aa0ba902b7")
            .sampled(false)
            .build();
    var span = tracer.spanBuilder().setParent(unsampledCaller).name("place-order").start();
    WorkOrigin origin;
    try (var _ = tracer.withSpan(span)) {
      origin = linkedWork.captureOrigin().orElseThrow();
    } finally {
      span.end();
    }

    assertThat(origin.sampled()).isFalse();
    assertThat(origin.toText()).contains("-00 ");
  }

  @Test
  @DisplayName("outside a traced operation there is no origin to capture")
  void capturesNothingOutsideTrace() {
    assertThat(linkedWork.captureOrigin()).isEmpty();
  }

  @Test
  @DisplayName("the timer is named stratum.linked.work and tagged with the work's name only")
  void timerIsTaggedWithWorkName() {
    var context = linkedWork.observation(INVOICE_EXPORT, List.of()).getContext();

    assertThat(context.getName()).isEqualTo(ObservabilityConstants.LINKED_WORK_OBSERVATION);
    assertThat(context.getLowCardinalityKeyValues())
        .containsExactly(KeyValue.of(ObservabilityConstants.LINKED_WORK_TAG, INVOICE_EXPORT));
  }

  @Test
  @DisplayName("a work name with data or unsafe characters is refused before anything runs")
  void refusesUnsafeWorkName() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> linkedWork.run("export for order 42", List.of(), () -> {}));
  }
}
