package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.AccessLevel;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * Keeps data values from database errors out of logs, spans and responses: it wraps a data source
 * and every JDBC object it hands out, and gives the service a clean copy of every error.
 */
@StratumInternal
public class JdbcBoundary {

  /** The generated wrapper classes and the factory of each type's wrappers. */
  private final JdbcWrapperClasses wrapperClasses;

  /** The factory of each wrapped JDBC type's wrappers, generated once at startup. */
  private final Map<Class<?>, BiFunction<Object, JdbcWrapper, JdbcWrapper>> factories;

  /** The JDBC types the boundary wraps, the most specific first. */
  private final List<Class<?>> wrappedTypes;

  /** Builds the clean copy of each error. */
  private final SqlExceptionCleaner cleaner;

  /** The time limits of the data source's statements, or {@code null} when it has none. */
  @Getter(AccessLevel.PACKAGE)
  private final @Nullable StatementLimits limits;

  /** Gives each statement's execute a span when its trace is sampled; none without tracing. */
  @Getter(AccessLevel.PACKAGE)
  private final StatementSpans spans;

  /** The wrapped JDBC type a driver class implements, worked out once per class. */
  private final ClassValue<Optional<Class<?>>> wrappedTypeByClass =
      new ClassValue<>() {
        @Override
        protected Optional<Class<?>> computeValue(Class<?> driverClass) {
          return wrappedTypes.stream()
              .filter(type -> type.isAssignableFrom(driverClass))
              .findFirst();
        }
      };

  /**
   * Creates the boundary from the wrapper classes generated at startup.
   *
   * @param wrapperClasses the generated wrapper of each JDBC type
   * @param constraintReaders the readers of the violated constraint's name, one per database
   */
  public JdbcBoundary(
      JdbcWrapperClasses wrapperClasses,
      List<Function<SQLException, Optional<String>>> constraintReaders) {
    this.wrapperClasses = wrapperClasses;
    this.factories = wrapperClasses.wrapperFactories();
    this.wrappedTypes = JdbcWrapperClasses.WRAPPED_TYPES;
    this.cleaner = new SqlExceptionCleaner(constraintReaders);
    this.limits = null;
    this.spans = StatementSpans.NONE;
  }

  /**
   * Creates a boundary that shares another's wrappers and cleaner, with its own limits and spans.
   */
  private JdbcBoundary(
      JdbcBoundary shared, @Nullable StatementLimits limits, StatementSpans spans) {
    this.wrapperClasses = shared.wrapperClasses;
    this.factories = shared.factories;
    this.wrappedTypes = shared.wrappedTypes;
    this.cleaner = shared.cleaner;
    this.limits = limits;
    this.spans = spans;
  }

  /**
   * Returns a boundary for one data source bean, which also limits how long each of its statements
   * runs.
   *
   * @param statementLimits the time limits of the data source's statements
   * @return the boundary, which shares this one's wrappers and cleaner
   */
  public JdbcBoundary withLimits(StatementLimits statementLimits) {
    return new JdbcBoundary(this, statementLimits, spans);
  }

  /**
   * Returns a boundary that also gives each statement's execute a span when its trace is sampled.
   *
   * @param statementSpans the spans of the data source's statements
   * @return the boundary, which shares this one's wrappers, cleaner and limits
   */
  public JdbcBoundary withSpans(StatementSpans statementSpans) {
    return new JdbcBoundary(this, limits, statementSpans);
  }

  /**
   * Wraps a data source, so that every connection it gives and every error it throws is clean.
   *
   * @param dataSource the pool or data source to wrap
   * @return the wrapper
   */
  public DataSource wrap(DataSource dataSource) {
    return wrapperClasses.wrap(dataSource, this);
  }

  /**
   * Returns the clean copy of an error.
   *
   * @param failure the driver's or pool's error
   * @return its clean copy
   */
  public SQLException cleaned(SQLException failure) {
    return cleaner.cleaned(failure);
  }

  /**
   * Wraps an object a JDBC call returned in the generated wrapper of its type; a connection gets
   * its data source's network timeout.
   *
   * @param returned the driver's or pool's object
   * @param type the JDBC type to wrap it as
   * @param parent the wrapper whose call returned it
   * @return the wrapper
   * @throws SQLException the clean copy of the driver's error when the network timeout cannot be
   *     set; the connection is then returned
   */
  JdbcWrapper wrapResult(Object returned, Class<?> type, JdbcWrapper parent) throws SQLException {
    var wrapper = factories.get(type).apply(returned, parent);
    if (wrapper instanceof ConnectionWrapper connection) {
      connection.onBorrow();
    }
    return wrapper;
  }

  /**
   * Returns the wrapped JDBC type an object implements, the most specific first.
   *
   * @param returned an object a JDBC call returned as {@code Object}
   * @return the type, or {@code null} when the boundary wraps none of its types
   */
  @Nullable Class<?> wrappedTypeOf(Object returned) {
    return wrappedTypeByClass.get(returned.getClass()).orElse(null);
  }
}
