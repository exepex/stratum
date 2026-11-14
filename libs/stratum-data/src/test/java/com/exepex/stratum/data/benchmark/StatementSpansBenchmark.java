package com.exepex.stratum.data.benchmark;

import com.exepex.stratum.data.StatementSpans;
import com.exepex.stratum.data.TracerStatementSpans;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Measures what a statement's span costs with no database round trip, which gates the span budget
 * of 3 µs per traced statement: the span hook alone, and an execute through the boundary on stub
 * JDBC objects that answer at once. The tracer is OpenTelemetry's SDK behind Micrometer's bridge,
 * with the batch processor a service has and an exporter that drops every span. The sides: {@code
 * none}, the boundary with no spans, as without tracing; {@code untraced}, spans on but no request;
 * {@code unsampled}, a request that is not sampled; {@code sampled}, a sampled request.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class StatementSpansBenchmark {

  /** The SQL every execute runs. */
  private static final String QUERY = "SELECT id FROM customers WHERE email = ?";

  /**
   * Starts and ends one statement span through the hook, with no JDBC call, on one thread.
   *
   * @param tracing the side's spans
   * @param request this thread's request
   * @return the started span, or no span, which JMH consumes
   */
  @Benchmark
  @Threads(1)
  public Object hookOnly(Tracing tracing, Request request) {
    return startAndEnd(tracing);
  }

  /**
   * Starts and ends one statement span through the hook on 16 threads at once, which share one
   * tracer and its export queue, as a service's request threads do.
   *
   * @param tracing the side's spans
   * @param request this thread's request
   * @return the started span, or no span, which JMH consumes
   */
  @Benchmark
  @Threads(16)
  public Object hookOnly16(Tracing tracing, Request request) {
    return startAndEnd(tracing);
  }

  /**
   * Runs a prepared statement's execute through the boundary, on stubs that answer at once.
   *
   * @param request this thread's request and statement
   * @return the result, which JMH consumes
   * @throws SQLException never, from the stubs
   */
  @Benchmark
  @Threads(1)
  public ResultSet execute(Request request) throws SQLException {
    return request.statement.executeQuery();
  }

  /** Starts and ends one span through the side's hook. */
  private Object startAndEnd(Tracing tracing) {
    var span = tracing.spans.start(QUERY, List.of(), tracing.connection);
    if (span != StatementSpans.NO_SPAN) {
      tracing.spans.end(span, null);
    }
    return span;
  }

  /** One side's tracer and spans, which every thread shares. */
  @State(Scope.Benchmark)
  public static class Tracing {

    /** See the class description. */
    @Param({"none", "untraced", "unsampled", "sampled"})
    public String side;

    /** The SDK's tracer provider. */
    private SdkTracerProvider provider;

    /** Micrometer's tracer over the SDK. */
    OtelTracer tracer;

    /** The side's spans. */
    StatementSpans spans;

    /** The stub connection, which reports a database name and product. */
    Connection connection;

    /** Builds the tracer and the spans. */
    @Setup(Level.Trial)
    public void start() {
      provider =
          SdkTracerProvider.builder()
              .setSampler(Sampler.traceIdRatioBased("unsampled".equals(side) ? 0.0 : 1.0))
              .addSpanProcessor(BatchSpanProcessor.builder(new DroppingExporter()).build())
              .build();
      tracer =
          new OtelTracer(provider.get("benchmark"), new OtelCurrentTraceContext(), event -> {});
      var beans = new StaticListableBeanFactory();
      beans.addBean("tracer", tracer);
      spans =
          "none".equals(side)
              ? StatementSpans.NONE
              : new TracerStatementSpans(beans.getBeanProvider(Tracer.class));
      connection = JdbcStubs.stubConnection();
    }

    /**
     * Returns whether this side runs its statements in a request.
     *
     * @return {@code true} for the unsampled and sampled sides
     */
    boolean inRequest() {
      return !"none".equals(side) && !"untraced".equals(side);
    }

    /** Closes the tracer. */
    @TearDown(Level.Trial)
    public void stop() {
      provider.close();
    }
  }

  /** One thread's open request and its statement through the boundary. */
  @State(Scope.Thread)
  public static class Request {

    /** The request's root span, open for the whole run; {@code null} without a request. */
    private Span root;

    /** The scope that makes the request current on this thread. */
    private Tracer.SpanInScope scope;

    /** The prepared statement, through the boundary. */
    PreparedStatement statement;

    /**
     * Builds this thread's statement and opens its request.
     *
     * @param tracing the side's tracer and spans
     * @throws SQLException never, from the stubs
     */
    @Setup(Level.Trial)
    public void start(Tracing tracing) throws SQLException {
      statement = JdbcStubs.boundaryStatement(tracing.spans, tracing.connection, QUERY);
      if (tracing.inRequest()) {
        root = tracing.tracer.nextSpan().name("request").start();
        scope = tracing.tracer.withSpan(root);
      }
    }

    /** Closes the request. */
    @TearDown(Level.Trial)
    public void stop() {
      if (scope != null) {
        scope.close();
        root.end();
      }
    }
  }

  /** Drops every span it is given. */
  static final class DroppingExporter implements SpanExporter {

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }
}
