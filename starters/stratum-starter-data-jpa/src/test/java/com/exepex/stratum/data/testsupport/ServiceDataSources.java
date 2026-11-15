package com.exepex.stratum.data.testsupport;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import lombok.experimental.UtilityClass;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * A service's own data source, plain, primary or routing, which makes Spring Boot create none,
 * connected to the test database.
 */
@UtilityClass
public class ServiceDataSources {

  /** The key of the one pool of the service's own routing data source. */
  public static final String ROUTER_KEY = "orders";

  /**
   * Returns a pool of the test database.
   *
   * @param details the test database's connection
   * @return the pool
   */
  public static DataSource poolOf(JdbcConnectionDetails details) {
    var pool = new HikariDataSource();
    pool.setJdbcUrl(details.getJdbcUrl());
    pool.setUsername(details.getUsername());
    pool.setPassword(details.getPassword());
    return pool;
  }

  /** A service's own plain data source. */
  @Configuration(proxyBeanMethods = false)
  public static class Plain {

    /**
     * Returns the service's pool.
     *
     * @param details the test database's connection
     * @return the pool
     */
    @Bean
    DataSource ownDataSource(JdbcConnectionDetails details) {
      return poolOf(details);
    }
  }

  /** A service's own primary data source. */
  @Configuration(proxyBeanMethods = false)
  public static class OwnPrimary {

    /**
     * Returns the service's pool.
     *
     * @param details the test database's connection
     * @return the pool
     */
    @Bean
    @Primary
    DataSource ownPrimary(JdbcConnectionDetails details) {
      return poolOf(details);
    }
  }

  /** A service's own routing data source, its only data source. */
  @Configuration(proxyBeanMethods = false)
  public static class OwnRouter {

    /**
     * Returns the service's routing data source, over one pool.
     *
     * @param details the test database's connection
     * @return the routing data source
     */
    @Bean
    DataSource dataSource(JdbcConnectionDetails details) {
      var router = new OneKeyRouter();
      router.setTargetDataSources(Map.of(ROUTER_KEY, poolOf(details)));
      return router;
    }
  }

  /** A routing data source that always picks its one pool. */
  static class OneKeyRouter extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
      return ROUTER_KEY;
    }
  }
}
