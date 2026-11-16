package com.exepex.stratum.data.autoconfigure;

import static com.exepex.stratum.data.testsupport.NamedPoolContexts.poolOf;
import static com.exepex.stratum.data.testsupport.NamedPoolContexts.withBatchPool;
import static com.exepex.stratum.data.testsupport.NamedPoolContexts.withMainPool;
import static com.exepex.stratum.data.testsupport.NamedPoolTestData.BATCH_DATA_SOURCE;
import static com.exepex.stratum.data.testsupport.NamedPoolTestData.BATCH_DETAILS;
import static com.exepex.stratum.data.testsupport.NamedPoolTestData.BATCH_KEYS;
import static com.exepex.stratum.data.testsupport.NamedPoolTestData.BATCH_MANAGER;
import static com.exepex.stratum.data.testsupport.NamedPoolTestData.URL;
import static com.exepex.stratum.data.testsupport.NamedPoolTestData.withVariables;
import static com.exepex.stratum.test.ErrorCodeChecks.startupFailureOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.data.PoolConnectionDetails;
import com.exepex.stratum.data.testsupport.NamedPoolContexts.Injected;
import com.exepex.stratum.data.testsupport.ReportsPoolCustomizer;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@DisplayName("The named pools of stratum.data.sources get beans that only a qualifier reaches")
class NamedPoolRegistrarConfigurationTest {

  /** The main pool's bean, which Spring Boot creates. */
  private static final String MAIN_BEAN = DataConstants.MAIN_BEAN;

  /** How many connections a named pool opens at most unless its keys say otherwise. */
  private static final int STANDARD_MAXIMUM = 10;

  /** The maximum a pool's own HikariCP key sets. */
  private static final int OWN_MAXIMUM = 4;

  @Nested
  @DisplayName("injection")
  class Injection {

    @Test
    @DisplayName("without a qualifier, a data source and a transaction manager are the main pool's")
    void plainInjectionGetsMain() {
      withBatchPool()
          .withBean(Injected.class)
          .run(context -> assertPlainInjectionGetsMain(context));
    }

    /** Checks what plainInjectionGetsMain expects of the started application. */
    private void assertPlainInjectionGetsMain(AssertableApplicationContext context) {
      var injected = context.getBean(Injected.class);
      assertThat(injected.dataSource()).isSameAs(context.getBean(MAIN_BEAN));
      assertThat(injected.manager()).isSameAs(context.getBean("transactionManager"));
      assertThat(((DataSourceTransactionManager) injected.manager()).getDataSource())
          .isSameAs(context.getBean(MAIN_BEAN));
    }

    @Test
    @DisplayName("with a qualifier, the data source and transaction manager are the pool's")
    void qualifierGetsPool() {
      withBatchPool().withBean(Injected.class).run(context -> assertQualifierGetsPool(context));
    }

    /** Checks what qualifierGetsPool expects of the started application. */
    private void assertQualifierGetsPool(AssertableApplicationContext context) {
      var injected = context.getBean(Injected.class);
      assertThat(injected.batch()).isSameAs(context.getBean(BATCH_DATA_SOURCE));
      assertThat(((DataSourceTransactionManager) injected.batchManager()).getDataSource())
          .isSameAs(context.getBean(BATCH_DATA_SOURCE));
    }

    @Test
    @DisplayName("a list of data sources, without a qualifier, holds the main pool only")
    void listGetsMain() {
      withBatchPool()
          .withBean(Listed.class)
          .run(
              context ->
                  assertThat(context.getBean(Listed.class).dataSources())
                      .containsExactly(context.getBean(MAIN_BEAN, DataSource.class)));
    }

    @Test
    @DisplayName("Spring Boot still creates its main pool and connection details with two pools")
    void mainStillExists() {
      withBatchPool()
          .withPropertyValues("stratum.data.sources.reports.url=" + URL)
          .run(
              context ->
                  assertThat(context)
                      .hasNotFailed()
                      .hasBean(MAIN_BEAN)
                      .hasBean("jdbcConnectionDetails")
                      .hasBean("transactionManager")
                      .hasBean(BATCH_DATA_SOURCE)
                      .hasBean("reportsDataSource"));
    }

    @Test
    @DisplayName("every data source bean is listed, as health and metrics list them")
    void everyPoolIsListed() {
      withBatchPool()
          .run(
              context ->
                  assertThat(context.getBeansOfType(DataSource.class))
                      .containsOnlyKeys(MAIN_BEAN, BATCH_DATA_SOURCE));
    }
  }

  @Nested
  @DisplayName("the pool")
  class Pool {

    @Test
    @DisplayName("is built cold: no connection is opened before the first borrow")
    void builtCold() {
      withBatchPool()
          .run(context -> assertThat(poolOf(context, BATCH_DATA_SOURCE).isRunning()).isFalse());
    }

    @Test
    @DisplayName("keeps no idle connection, opens at most 10 and is named after the pool")
    void hasDefaults() {
      withBatchPool().run(context -> assertHasDefaults(context));
    }

    /** Checks what hasDefaults expects of the started application. */
    private void assertHasDefaults(AssertableApplicationContext context) throws SQLException {
      var pool = poolOf(context, BATCH_DATA_SOURCE);
      assertThat(pool.getMinimumIdle()).isZero();
      assertThat(pool.getMaximumPoolSize()).isEqualTo(STANDARD_MAXIMUM);
      assertThat(pool.getPoolName()).isEqualTo("batch");
      assertThat(pool.getJdbcUrl()).isEqualTo(URL);
    }

    @Test
    @DisplayName("takes its HikariCP keys, never the main pool's")
    void takesItsOwnKeys() {
      withBatchPool()
          .withPropertyValues(
              BATCH_KEYS + "hikari.maximum-pool-size=" + OWN_MAXIMUM,
              "spring.datasource.hikari.minimum-idle=2")
          .run(context -> assertTakesItsOwnKeys(context));
    }

    /** Checks what takesItsOwnKeys expects of the started application. */
    private void assertTakesItsOwnKeys(AssertableApplicationContext context) throws SQLException {
      assertThat(poolOf(context, BATCH_DATA_SOURCE).getMaximumPoolSize()).isEqualTo(OWN_MAXIMUM);
      assertThat(poolOf(context, BATCH_DATA_SOURCE).getMinimumIdle()).isZero();
    }

    @Test
    @DisplayName("an environment variable in Spring Boot's spelling sets the pool's HikariCP key")
    void takesVariable() {
      withBatchPool()
          .withInitializer(
              context ->
                  context
                      .getEnvironment()
                      .getPropertySources()
                      .addFirst(
                          withVariables(
                                  Map.of(
                                      "STRATUM_DATA_SOURCES_BATCH_HIKARI_MAXIMUM_POOL_SIZE",
                                      String.valueOf(OWN_MAXIMUM)))
                              .getPropertySources()
                              .get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)))
          .run(
              context ->
                  assertThat(poolOf(context, BATCH_DATA_SOURCE).getMaximumPoolSize())
                      .isEqualTo(OWN_MAXIMUM));
    }

    @Test
    @DisplayName("a pool a Java customizer adds, with no key at all, gets its beans and settings")
    void javaOnlyPool() {
      withMainPool()
          .withPropertyValues(
              DataConstants.QUERY_TIMEOUT + "=" + ReportsPoolCustomizer.MARKER.toMillis() + "ms")
          .run(
              context ->
                  assertThat(poolOf(context, "reportsDataSource").getMaximumPoolSize())
                      .hasToString(ReportsPoolCustomizer.REPORTS_MAXIMUM));
    }

    @Test
    @DisplayName(
        "a pool with no url and no connection details bean stops startup with SX-DATA-1004")
    void needsConnection() {
      withMainPool()
          .withPropertyValues(BATCH_KEYS + "query-timeout=5s")
          .run(
              context ->
                  assertThat(startupFailureOf(context, DataErrorCode.POOL_SETTING_INVALID))
                      .hasMessageContaining(BATCH_KEYS + "url"));
    }

    @Test
    @DisplayName("a leak alarm threshold HikariCP would turn off stops startup with SX-DATA-1002")
    void leakAlarmTurnedOff() {
      withBatchPool()
          .withPropertyValues(BATCH_KEYS + "hikari.leak-detection-threshold=500")
          .run(
              context ->
                  assertThat(startupFailureOf(context, DataErrorCode.LEAK_ALARM_OFF))
                      .hasMessageContaining(BATCH_KEYS + "hikari.leak-detection-threshold"));
    }

    @Test
    @DisplayName("with the module off, no pool is registered even with keys")
    void moduleOff() {
      withBatchPool()
          .withPropertyValues(DataConstants.ENABLED + "=false")
          .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(BATCH_DATA_SOURCE));
    }
  }

  @Nested
  @DisplayName("the service's own beans")
  class ServiceBeans {

    /** The bean names Stratum keeps for the pool {@code batch}. */
    private static final List<String> RESERVED_NAMES =
        List.of(BATCH_DATA_SOURCE, BATCH_DETAILS, BATCH_MANAGER);

    @Test
    @DisplayName("a connection details bean that is no default candidate adds the pool, no key")
    void detailsBeanAddsPool() {
      withMainPool()
          .withUserConfiguration(OwnDetails.class)
          .run(context -> assertDetailsBeanAddsPool(context));
    }

    /** Checks what detailsBeanAddsPool expects of the started application. */
    private void assertDetailsBeanAddsPool(AssertableApplicationContext context)
        throws SQLException {
      assertThat(context).hasNotFailed().hasBean(MAIN_BEAN);
      assertThat(poolOf(context, BATCH_DATA_SOURCE).getJdbcUrl()).isEqualTo(OWN_URL);
    }

    @Test
    @DisplayName("a connection details bean that is a default candidate adds no pool")
    void defaultDetailsBeanAddsNoPool() {
      withMainPool()
          .withBean(
              "reportsJdbcConnectionDetails",
              JdbcConnectionDetails.class,
              () -> new PoolConnectionDetails(OWN_URL, null, null))
          .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("reportsDataSource"));
    }

    @Test
    @DisplayName("its own data source of a pool's name, no default candidate, is kept")
    void keepsOwnDataSource() {
      withBatchPool()
          .withUserConfiguration(OwnDataSource.class)
          .run(
              context ->
                  assertThat(poolOf(context, BATCH_DATA_SOURCE).getPoolName()).isEqualTo("own"));
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("RESERVED_NAMES")
    @DisplayName("a default candidate of a pool's bean name stops startup with SX-DATA-1006")
    void rejectsDefaultCandidate(String beanName) {
      withBatchPool()
          .withBean(beanName, Object.class, Object::new)
          .run(
              context ->
                  assertThat(startupFailureOf(context, DataErrorCode.POOL_BEAN_DEFAULT_CANDIDATE))
                      .hasMessageContaining(beanName));
    }
  }

  /** The URL of the service's own connection details. */
  private static final String OWN_URL = "jdbc:postgresql://localhost:2/own";

  /** The service's own connection details for the pool, with no key. */
  @Configuration(proxyBeanMethods = false)
  static class OwnDetails {

    /**
     * Returns the pool's connection, which no key sets.
     *
     * @return the connection
     */
    @Bean(defaultCandidate = false)
    JdbcConnectionDetails batchJdbcConnectionDetails() {
      return new PoolConnectionDetails(OWN_URL, null, null);
    }
  }

  /** The service's own data source for the pool. */
  @Configuration(proxyBeanMethods = false)
  static class OwnDataSource {

    /**
     * Returns the service's own pool, named so the test can tell it from Stratum's.
     *
     * @return the pool
     */
    @Bean(defaultCandidate = false)
    HikariDataSource batchDataSource() {
      var pool = new HikariDataSource();
      pool.setJdbcUrl(URL);
      pool.setPoolName("own");
      return pool;
    }
  }

  /**
   * A service bean that takes every data source without a qualifier.
   *
   * @param dataSources the data sources
   */
  record Listed(java.util.List<DataSource> dataSources) {}
}
