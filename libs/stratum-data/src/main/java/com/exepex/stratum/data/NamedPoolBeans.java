package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * Builds a named pool's HikariCP pool and its transaction manager, the way Spring Boot builds the
 * main pool's, with the pool's own settings.
 */
@StratumInternal
@RequiredArgsConstructor
public class NamedPoolBeans {

  /** How many connections a named pool opens at most, unless its settings say otherwise. */
  static final int STANDARD_MAXIMUM_POOL_SIZE = 10;

  /** Where HikariCP's settings start, in the pool's settings and in its keys. */
  private static final String HIKARI = "hikari";

  /** The application's environment, with the pools' keys. */
  private final Environment environment;

  /** Finds each pool's connection details, its data source and the transaction customizers. */
  private final BeanFactory beanFactory;

  /**
   * Returns a named pool that has not started: HikariCP opens its first connection at the first
   * borrow, never while the pool is built. It connects with the pool's connection details bean,
   * keeps no idle connection, opens at most 10 and is named after the pool, unless its HikariCP
   * keys, then its Java settings, say otherwise.
   *
   * @param name the pool's name
   * @param settings the pool's settings
   * @return the pool
   */
  HikariDataSource dataSource(String name, PoolSettings settings) {
    var details =
        beanFactory.getBean(name + DataConstants.DETAILS_SUFFIX, JdbcConnectionDetails.class);
    var pool =
        DataSourceBuilder.create()
            .type(HikariDataSource.class)
            .url(details.getJdbcUrl())
            .username(details.getUsername())
            .password(details.getPassword())
            .driverClassName(details.getDriverClassName())
            .build();
    pool.setMinimumIdle(0);
    pool.setMaximumPoolSize(STANDARD_MAXIMUM_POOL_SIZE);
    pool.setPoolName(name);
    Binder.get(environment)
        .bind(DataConstants.SOURCES + "." + name + "." + HIKARI, Bindable.ofInstance(pool));
    new Binder(new MapConfigurationPropertySource(prefixed(settings.hikari())))
        .bind(HIKARI, Bindable.ofInstance(pool));
    return pool;
  }

  /**
   * Returns a named pool's transaction manager, built as Spring Boot builds the main pool's: a
   * {@code JdbcTransactionManager}, or a {@code DataSourceTransactionManager} with {@code
   * spring.dao.exceptiontranslation.enabled=false}, changed by Boot's transaction customizers,
   * which apply the {@code spring.transaction.*} keys. The pool's {@code transaction-timeout}, when
   * set, comes last and wins over {@code spring.transaction.default-timeout}.
   *
   * @param name the pool's name
   * @param settings the pool's settings
   * @return the transaction manager of the pool's data source bean
   */
  DataSourceTransactionManager transactionManager(String name, PoolSettings settings) {
    var dataSource = beanFactory.getBean(name + DataConstants.DATA_SOURCE_SUFFIX, DataSource.class);
    var manager =
        environment.getProperty(DataConstants.EXCEPTION_TRANSLATION, Boolean.class, Boolean.TRUE)
            ? new JdbcTransactionManager(dataSource)
            : new DataSourceTransactionManager(dataSource);
    beanFactory
        .getBeanProvider(TransactionManagerCustomizers.class)
        .ifAvailable(customizers -> customizers.customize(manager));
    var timeout = settings.transactionTimeout();
    if (timeout != null) {
      manager.setDefaultTimeout(seconds(timeout));
    }
    return manager;
  }

  /** Returns a time limit in whole seconds, as Spring Boot reads its own default timeout. */
  private int seconds(Duration timeout) {
    return (int) Math.min(timeout.toSeconds(), Integer.MAX_VALUE);
  }

  /** Returns HikariCP's settings with {@code hikari.} before each key, for the binder. */
  private Map<String, String> prefixed(Map<String, String> hikari) {
    return hikari.entrySet().stream()
        .collect(Collectors.toMap(entry -> HIKARI + "." + entry.getKey(), Map.Entry::getValue));
  }
}
