package com.exepex.stratum.data.it.routing;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * A service that routes between two pools and fetches connections lazily, each a data source bean,
 * so that the boundary wraps all four.
 */
@SpringBootApplication
public class RoutingApplication {

  /** The key of the pool every call is routed to. */
  static final String PRIMARY_POOL = "primary";

  /**
   * Returns the first pool.
   *
   * @param connectionDetails the test database
   * @return the pool
   */
  @Bean
  DataSource primaryPool(JdbcConnectionDetails connectionDetails) {
    return poolFor(connectionDetails);
  }

  /**
   * Returns the second pool.
   *
   * @param connectionDetails the test database
   * @return the pool
   */
  @Bean
  DataSource replicaPool(JdbcConnectionDetails connectionDetails) {
    return poolFor(connectionDetails);
  }

  /**
   * Returns the data source that routes every call to the first pool.
   *
   * @param primaryPool the first pool
   * @param replicaPool the second pool
   * @return the routing data source
   */
  @Bean
  DataSource routingDataSource(
      @Qualifier("primaryPool") DataSource primaryPool,
      @Qualifier("replicaPool") DataSource replicaPool) {
    var routing = new PrimaryRouting();
    routing.setTargetDataSources(Map.of(PRIMARY_POOL, primaryPool, "replica", replicaPool));
    routing.setDefaultTargetDataSource(primaryPool);
    return routing;
  }

  /**
   * Returns the lazy proxy over the routing data source, the one the service uses.
   *
   * @param routingDataSource the routing data source
   * @return the proxy
   */
  @Bean
  @Primary
  DataSource dataSource(@Qualifier("routingDataSource") DataSource routingDataSource) {
    return new LazyConnectionDataSourceProxy(routingDataSource);
  }

  /** Returns a pool of the test database. */
  private DataSource poolFor(JdbcConnectionDetails connectionDetails) {
    var pool = new HikariDataSource();
    pool.setJdbcUrl(connectionDetails.getJdbcUrl());
    pool.setUsername(connectionDetails.getUsername());
    pool.setPassword(connectionDetails.getPassword());
    return pool;
  }

  /** Routes every call to the first pool. */
  static class PrimaryRouting extends AbstractRoutingDataSource {

    /**
     * Returns the key of the first pool.
     *
     * @return the key
     */
    @Override
    protected Object determineCurrentLookupKey() {
      return PRIMARY_POOL;
    }
  }
}
