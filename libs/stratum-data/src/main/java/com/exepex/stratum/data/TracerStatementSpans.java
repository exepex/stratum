package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Gives each statement's execute a client span through Micrometer Tracing, only when the current
 * span is sampled. The span carries the database system, its name and the operation the SQL starts
 * with: no SQL text, value or table. It makes no Observation, so no meter is added. The tracer is
 * looked up at the first statement, since the boundary is set up before any tracer exists.
 */
@StratumInternal
@RequiredArgsConstructor
public final class TracerStatementSpans implements StatementSpans {

  /** The {@code db.system.name} of each product name a driver reports, in lower case. */
  private static final Map<String, String> SYSTEMS =
      Map.of(
          "postgresql", "postgresql",
          "h2", "h2",
          "mysql", "mysql",
          "mariadb", "mariadb",
          "oracle", "oracle.db",
          "microsoft sql server", "microsoft.sql_server");

  /** The {@code db.system.name} of a product not in {@link #SYSTEMS}. */
  private static final String OTHER_SYSTEM = "other_sql";

  /** Finds the application's tracer. */
  private final ObjectProvider<Tracer> tracers;

  /** The tracer, once found. */
  private final AtomicReference<@Nullable Tracer> foundTracer = new AtomicReference<>();

  /** The data source's {@code db.system.name}, once read from a traced connection. */
  private final AtomicReference<@Nullable String> knownSystem = new AtomicReference<>();

  /**
   * Returns the spans of an application, which finds its tracer at the first statement. Only called
   * when Micrometer Tracing's OpenTelemetry bridge is present, so no other class refers to it.
   *
   * @param beanFactory the bean factory, which finds the tracer
   * @return the spans
   */
  static StatementSpans of(BeanFactory beanFactory) {
    return new TracerStatementSpans(beanFactory.getBeanProvider(Tracer.class));
  }

  @Override
  public Object start(String sql, List<String> batch, @Nullable Connection connection) {
    try {
      var tracer = tracer();
      var current = tracer == null ? null : tracer.currentSpan();
      if (current == null || !Boolean.TRUE.equals(current.context().sampled())) {
        return NO_SPAN;
      }
      var operation =
          (batch.isEmpty() ? QueryOperation.of(sql) : QueryOperation.ofBatch(batch)).name();
      var builder =
          tracer
              .spanBuilder()
              .setParent(current.context())
              .kind(Span.Kind.CLIENT)
              .name(operation)
              .tag(DataConstants.SPAN_SYSTEM, system(connection))
              .tag(DataConstants.SPAN_OPERATION, operation);
      var namespace = namespace(connection);
      if (namespace != null) {
        builder.tag(DataConstants.SPAN_NAMESPACE, namespace);
      }
      return builder.start();
    } catch (RuntimeException _) {
      return NO_SPAN;
    }
  }

  @Override
  public void end(Object span, @Nullable Throwable thrown) {
    try {
      var started = (Span) span;
      if (thrown != null) {
        started.error(thrown);
      }
      started.end();
    } catch (RuntimeException ignored) {
      // A span that cannot end is dropped; the statement's own result stands.
    }
  }

  /** Returns the tracer, looking it up until it exists. */
  private @Nullable Tracer tracer() {
    var tracer = foundTracer.get();
    if (tracer == null) {
      tracer = tracers.getIfAvailable();
      foundTracer.set(tracer);
    }
    return tracer;
  }

  /** Returns the data source's database system, read from a connection's metadata until known. */
  private String system(@Nullable Connection connection) {
    var system = knownSystem.get();
    if (system != null) {
      return system;
    }
    var product = productName(connection);
    if (product.isEmpty()) {
      return OTHER_SYSTEM;
    }
    system = SYSTEMS.getOrDefault(product, OTHER_SYSTEM);
    knownSystem.set(system);
    return system;
  }

  /** Returns the product name a connection's driver reports, in lower case, or an empty text. */
  private String productName(@Nullable Connection connection) {
    if (connection == null) {
      return "";
    }
    try {
      var metadata = connection.getMetaData();
      var product = metadata == null ? null : metadata.getDatabaseProductName();
      return product == null ? "" : product.toLowerCase(Locale.ROOT);
    } catch (SQLException _) {
      return "";
    }
  }

  /** Returns the connection's database name, which the driver keeps, or {@code null}. */
  private @Nullable String namespace(@Nullable Connection connection) {
    if (connection == null) {
      return null;
    }
    try {
      return connection.getCatalog();
    } catch (SQLException _) {
      return null;
    }
  }
}
