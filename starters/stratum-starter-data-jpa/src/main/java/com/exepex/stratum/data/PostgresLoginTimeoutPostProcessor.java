package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/**
 * Gives every HikariCP pool bean that connects to PostgreSQL a login limit from its own {@code
 * connection-timeout}: 3 seconds for Spring Boot's pool with Stratum's default. A pool whose
 * data-source properties hold {@code loginTimeout} keeps its own, and so does a URL with {@code
 * loginTimeout}. Each connection attempt with a limit runs on a thread of the driver's own.
 */
@Slf4j
@StratumInternal
public final class PostgresLoginTimeoutPostProcessor implements BeanPostProcessor, Ordered {

  /** Runs before the JDBC boundary, at -100, wraps the pool, so it still sees the pool itself. */
  private static final int ORDER = -200;

  /** The line breaks a logged bean name must not carry. */
  private static final String LINE_BREAKS = "[\r\n]";

  /**
   * Adds the login limit to a PostgreSQL pool that has not started yet; HikariCP refuses the change
   * once the pool has started, and such a pool keeps no limit.
   *
   * @param bean the bean, after its initialisation and after Spring Boot bound its settings
   * @param beanName its name
   * @return the bean itself
   */
  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof HikariDataSource) {
      limit((HikariConfig) bean, beanName);
    }
    return bean;
  }

  /** Adds the limit to a PostgreSQL pool whose properties and wait leave it without one. */
  private void limit(HikariConfig pool, String beanName) {
    if (!PostgresLoginTimeout.appliesTo(pool.getJdbcUrl())
        || pool.getDataSourceProperties().containsKey(PostgresLoginTimeout.LOGIN_TIMEOUT)
        || PostgresLoginTimeout.unlimited(pool.getConnectionTimeout())) {
      return;
    }
    try {
      pool.addDataSourceProperty(
          PostgresLoginTimeout.LOGIN_TIMEOUT,
          PostgresLoginTimeout.seconds(pool.getConnectionTimeout()));
    } catch (IllegalStateException started) {
      if (log.isDebugEnabled()) {
        log.debug(
            "The pool '{}' has already started, so it gets no login limit from Stratum",
            beanName.replaceAll(LINE_BREAKS, " "));
      }
    }
  }

  /**
   * Runs before the JDBC boundary wraps the pool.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return ORDER;
  }
}
