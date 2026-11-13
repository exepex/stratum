package com.exepex.stratum.data;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.sql.DataSource;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.util.ClassUtils;

/**
 * Wraps every {@code DataSource} bean in Stratum's JDBC boundary, so that no data value from a
 * database error reaches a log, a span or a response. Every library that reaches the database
 * through a data source bean is covered, such as Hibernate, {@code JdbcClient} and {@code
 * JdbcTemplate}. The errors keep their standard {@code java.sql} class, SQLState and vendor code;
 * on PostgreSQL, {@code getConstraintName()} keeps working. Each statement of a data source bean
 * also runs under a time limit ({@code stratum.data.query-timeout}, the code's own limit, and the
 * transaction's deadline), and its connection under a network timeout above it.
 *
 * <p>To skip a data source, declare your own bean named {@code stratumJdbcBoundary} with a filter
 * of bean names. A skipped data source has <strong>no</strong> boundary: its errors may carry data
 * values into logs, spans and responses. The filter sees bean names only; data sources that are no
 * beans are not wrapped by it.
 */
public final class JdbcBoundaryPostProcessor
    implements BeanPostProcessor,
        Ordered,
        BeanClassLoaderAware,
        BeanFactoryAware,
        EnvironmentAware,
        DisposableBean {

  /** A class of the PostgreSQL driver, whose presence turns on the PostgreSQL reader. */
  private static final String POSTGRES_EXCEPTION = "org.postgresql.util.PSQLException";

  /** Runs after Boot's pool settings and before Boot's lazy-connection proxy, at order 0. */
  private static final int ORDER = -100;

  /** Which data source beans, by name, get the boundary. */
  private final Predicate<String> wrapsBean;

  /** The boundary, built once Spring gives the class loader, before any bean is processed. */
  private @Nullable JdbcBoundary boundary;

  /** The class loader of the application's beans, which shows whether Micrometer is present. */
  private @Nullable ClassLoader classLoader;

  /** Finds the data source beans and the meter registry, for the statement limits. */
  @Setter private @Nullable BeanFactory beanFactory;

  /** The application's environment, which holds {@code stratum.data.query-timeout} and the mode. */
  @Setter private @Nullable Environment environment;

  /** Builds each data source bean's statement limits; {@code null} until the first bean. */
  private @Nullable StatementLimitsFactory limitsFactory;

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
    this.classLoader = classLoader;
    boundary = new JdbcBoundary(new JdbcWrapperClasses(), readers);
  }

  /** Stops the thread that cancels statements past their limit, when the application stops. */
  @Override
  public void destroy() {
    if (limitsFactory != null) {
      limitsFactory.close();
    }
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
      return boundaryFor(beanName).wrap(dataSource);
    }
    return bean;
  }

  /**
   * Returns the boundary of a data source bean, with the time limits of its statements once Spring
   * has given the bean factory and the environment.
   */
  private JdbcBoundary boundaryFor(String beanName) {
    var shared = readyBoundary();
    if (beanFactory == null || environment == null || classLoader == null) {
      return shared;
    }
    if (limitsFactory == null) {
      limitsFactory = new StatementLimitsFactory(environment, beanFactory, classLoader);
    }
    return shared.withLimits(limitsFactory.forPool(beanName));
  }

  /**
   * Returns the boundary around the connection Spring Boot builds for Flyway or Liquibase, from
   * their own {@code url} or {@code user} or from their connection details, and any other data
   * source as it is, such as a data source bean, which already has its boundary. On PostgreSQL,
   * that connection also gets the login limit of Boot's pool.
   *
   * @param migrationDataSource the data source the migration tool will use
   * @return the wrapped connection, or the data source itself
   */
  DataSource migrationDataSource(DataSource migrationDataSource) {
    if (!(migrationDataSource instanceof SimpleDriverDataSource connection)) {
      return migrationDataSource;
    }
    PostgresLoginTimeout.limit(connection, environment);
    return readyBoundary().wrap(connection);
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
