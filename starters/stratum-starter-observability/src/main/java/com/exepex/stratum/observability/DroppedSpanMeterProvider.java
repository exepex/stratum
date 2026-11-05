package com.exepex.stratum.observability;

import com.exepex.stratum.core.api.StratumInternal;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.MeterBuilder;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Counts the spans OpenTelemetry drops because its export queue is full, as the Micrometer counter
 * {@code stratum.observability.spans.dropped}. Spring Boot gives this provider to the batch span
 * processor, which records each drop in it. Only the span processor's meter is recorded; every
 * other OpenTelemetry meter stays a no-op, as without this provider. Nothing is exported from it:
 * Micrometer reads the count when it publishes.
 */
@StratumInternal
public final class DroppedSpanMeterProvider implements MeterProvider, MeterBinder {

  private static final AttributeKey<Boolean> DROPPED =
      AttributeKey.booleanKey(ObservabilityConstants.DROPPED_ATTRIBUTE);

  private final PullReader reader = new PullReader();

  private final SdkMeterProvider sdkMeterProvider =
      SdkMeterProvider.builder().registerMetricReader(reader).build();

  /**
   * Returns a recording meter for the span processor's scope and a no-op one for every other.
   *
   * @param instrumentationScopeName the library that asks for a meter
   * @return the meter's builder
   */
  @Override
  public MeterBuilder meterBuilder(String instrumentationScopeName) {
    return ObservabilityConstants.SPAN_PROCESSOR_METER.equals(instrumentationScopeName)
        ? sdkMeterProvider.meterBuilder(instrumentationScopeName)
        : MeterProvider.noop().meterBuilder(instrumentationScopeName);
  }

  /**
   * Registers the dropped-span counter, which reads {@link #droppedSpans()}.
   *
   * @param registry the service's meter registry
   */
  @Override
  public void bindTo(MeterRegistry registry) {
    FunctionCounter.builder(
            ObservabilityConstants.DROPPED_SPANS_METRIC,
            this,
            DroppedSpanMeterProvider::droppedSpans)
        .description("Spans lost because OpenTelemetry's export queue was full")
        .baseUnit("spans")
        .register(registry);
  }

  /**
   * Returns how many spans the batch span processor has dropped since startup.
   *
   * @return the number of dropped spans
   */
  public long droppedSpans() {
    return reader.registration.get().collectAllMetrics().stream()
        .filter(
            metric -> ObservabilityConstants.PROCESSED_SPANS_INSTRUMENT.equals(metric.getName()))
        .flatMap(metric -> metric.getLongSumData().getPoints().stream())
        .filter(point -> Boolean.TRUE.equals(point.getAttributes().get(DROPPED)))
        .mapToLong(LongPointData::getValue)
        .sum();
  }

  /** Keeps the SDK's handle to its metrics, read on demand; starts no thread. */
  private static final class PullReader implements MetricReader {

    private final AtomicReference<CollectionRegistration> registration =
        new AtomicReference<>(CollectionRegistration.noop());

    @Override
    public void register(CollectionRegistration collectionRegistration) {
      registration.set(collectionRegistration);
    }

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
      return AggregationTemporality.CUMULATIVE;
    }

    @Override
    public CompletableResultCode forceFlush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }
}
