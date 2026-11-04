package com.exepex.stratum.data;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.util.ClassUtils;

/**
 * Wraps every {@code DataSource} bean in Stratum's JDBC boundary, so that no data value from a
 * database error reaches a log, a span or a response. Every library that reaches the database
 * through a data source bean is covered, such as Hibernate, {@code JdbcClient} and {@code
 * JdbcTemplate}. The errors keep their standard {@code java.sql} class, SQLState and vendor code;
 * on PostgreSQL, {@code getConstraintName()} keeps working.
 *
 * <p>To skip a data source, declare your own bean named {@code stratumJdbcBoundary} with a filter
 * of bean names. A skipped data source has <strong>no</strong> boundary: its errors may carry data
 * values into logs, spans and responses. The filter sees bean names only; data sources that are no
 * beans are not wrapped by it.
 */
public final class JdbcBoundaryPostProcessor
    implements BeanPostProcessor, Ordered, BeanClassLoaderAware {

  /** A class of the PostgreSQL driver, whose presence turns on the PostgreSQL reader. */
  private static final String POSTGRES_EXCEPTION = "org.postgresql.util.PSQLException";

  /** Runs after Boot's pool settings and before Boot's lazy-connection proxy, at order 0. */
  private static final int ORDER = -100;

  /** Which data source beans, by name, get the boundary. */
  private final Predicate<String> wrapsBean;

  /** The boundary, built once Spring gives the class loader, before any bean is processed. */
  private @Nullable JdbcBoundary boundary;

  /**
   * Creates the post-processor.
   *
   * @param wrapsBean returns {@code true} for the name of each data source bean to wrap; a bean it
   *     rejects gets no boundary
   */
  public JdbcBoundaryPostProcessor(Predicate<String> wrapsBean) {
    this.wrapsBean = Objects.requireNonNull(wrapsBean, "wrapsBean must not be null");
  }

  /**
   * Builds the boundary, with the PostgreSQL reader when the driver is on the class path.
   *
   * @param classLoader the class loader of the application's beans
   */
  @Override
  public void setBeanClassLoader(ClassLoader classLoader) {
    List<Function<SQLException, Optional<String>>> readers =
        ClassUtils.isPresent(POSTGRES_EXCEPTION, classLoader)
            ? List.of(new PostgresConstraintNameReader())
            : List.of();
    boundary = new JdbcBoundary(new JdbcWrapperClasses(), readers);
  }

  /**
   * Returns the boundary around a data source bean the filter accepts, and any other bean as it is.
   *
   * @param bean the bean, after its initialisation
   * @param beanName its name
   * @return the wrapped data source, or the bean
   */
  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof DataSource dataSource
        && !(bean instanceof JdbcWrapper)
        && wrapsBean.test(beanName)) {
      return readyBoundary().wrap(dataSource);
    }
    return bean;
  }

  /**
   * Returns the boundary around the connection Spring Boot builds for Flyway or Liquibase when
   * {@code spring.flyway.user} or {@code url}, or the Liquibase keys, are set, and any other data
   * source as it is, such as a data source bean, which already has its boundary.
   *
   * @param migrationDataSource the data source the migration tool will use
   * @return the wrapped connection, or the data source itself
   */
  DataSource migrationDataSource(DataSource migrationDataSource) {
    return migrationDataSource instanceof SimpleDriverDataSource
        ? readyBoundary().wrap(migrationDataSource)
        : migrationDataSource;
  }

  /** Returns the boundary, which Spring builds through the class loader before any bean exists. */
  private JdbcBoundary readyBoundary() {
    return Objects.requireNonNull(boundary, "the bean class loader is not set yet");
  }

  /**
   * Runs after Boot has applied the pool's settings and before Boot's lazy-connection proxy, so
   * that proxy, when it is on, wraps the boundary.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return ORDER;
  }
}
