package com.exepex.stratum.test;

import com.exepex.stratum.core.api.StratumInternal;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.sql.SQLException;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * A real OpenTelemetry tracer behind Micrometer Tracing's bridge, as the observability starter sets
 * up, with a sample rate the test picks and an exporter that keeps every ended span in memory. A
 * unit test builds one; a Spring test registers it as a bean and its {@link #tracer()} as the
 * {@link Tracer} bean.
 */
@StratumInternal
public final class TracedRequests implements AutoCloseable {

  /** The name of each request's root span. */
  public static final String REQUEST = "request";

  /** The spans that ended, kept in memory. */
  private final InMemorySpanExporter exporter = InMemorySpanExporter.create();

  /** The SDK's tracer provider, which samples at the test's rate. */
  private final SdkTracerProvider provider;

  /** Micrometer's tracer over the SDK, which the boundary's spans use. */
  private final OtelTracer otelTracer;

  /**
   * Creates a tracer that samples the given share of requests.
   *
   * @param sampleRate the share of requests sampled, from 0 to 1
   */
  public TracedRequests(double sampleRate) {
    provider =
        SdkTracerProvider.builder()
            .setSampler(Sampler.traceIdRatioBased(sampleRate))
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
    otelTracer = new OtelTracer(provider.get("test"), new OtelCurrentTraceContext(), event -> {});
  }

  /**
   * Returns a tracer that samples every request.
   *
   * @return the tracer
   */
  public static TracedRequests sampled() {
    return new TracedRequests(1.0);
  }

  /**
   * Returns a tracer that samples no request.
   *
   * @return the tracer
   */
  public static TracedRequests unsampled() {
    return new TracedRequests(0.0);
  }

  /**
   * Returns Micrometer's tracer.
   *
   * @return the tracer
   */
  public Tracer tracer() {
    return otelTracer;
  }

  /**
   * Returns a provider of the tracer, as a bean factory gives it.
   *
   * @return the provider
   */
  public ObjectProvider<Tracer> tracers() {
    var beans = new StaticListableBeanFactory();
    beans.addBean("tracer", otelTracer);
    return beans.getBeanProvider(Tracer.class);
  }

  /**
   * Runs work inside a request's root span, as a traced web request does.
   *
   * @param work the work
   * @throws SQLException what the work throws
   */
  public void inRequest(Work work) throws SQLException {
    var root = otelTracer.nextSpan().name(REQUEST).start();
    try (var _ = otelTracer.withSpan(root)) {
      work.run();
    } finally {
      root.end();
    }
  }

  /**
   * Returns the client spans that ended: one per statement execute.
   *
   * @return the spans, in the order they ended
   */
  public List<SpanData> statementSpans() {
    return QuerySpanAssertions.statementSpans(exporter.getFinishedSpanItems());
  }

  /**
   * Returns every span that ended.
   *
   * @return the spans, in the order they ended
   */
  public List<SpanData> allSpans() {
    return exporter.getFinishedSpanItems();
  }

  /** Forgets the spans that ended so far, such as those of startup or of an earlier test. */
  public void reset() {
    exporter.reset();
  }

  /** Closes the tracer provider. */
  @Override
  public void close() {
    provider.close();
  }

  /** Work that runs inside a request and may throw. */
  @StratumInternal
  @FunctionalInterface
  public interface Work {

    /**
     * Runs the work.
     *
     * @throws SQLException what the work throws
     */
    void run() throws SQLException;
  }
}
