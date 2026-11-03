package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.sql.DataSource;
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
   * Wraps an object a JDBC call returned in the generated wrapper of its type.
   *
   * @param returned the driver's or pool's object
   * @param type the JDBC type to wrap it as
   * @param parent the wrapper whose call returned it
   * @return the wrapper
   */
  JdbcWrapper wrapResult(Object returned, Class<?> type, JdbcWrapper parent) {
    return factories.get(type).apply(returned, parent);
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
