package com.exepex.stratum.data.autoconfigure;

import static com.exepex.stratum.data.testsupport.NamedPoolContexts.withBatchPool;
import static com.exepex.stratum.data.testsupport.NamedPoolContexts.withMainPool;
import static com.exepex.stratum.data.testsupport.NamedPoolTestData.BATCH_KEYS;
import static com.exepex.stratum.data.testsupport.NamedPoolTestData.ORACLE_URL_WITH_PASSWORD;
import static com.exepex.stratum.data.testsupport.NamedPoolTestData.SECRET;
import static com.exepex.stratum.data.testsupport.NamedPoolTestData.URL;
import static com.exepex.stratum.data.testsupport.NamedPoolTestData.URL_WITH_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.data.DataProperties;
import com.exepex.stratum.data.PoolCheck;
import com.exepex.stratum.test.LogLineCapture;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import javax.sql.DataSource;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("PoolCheck counts every pool against the cap and logs each one without secrets")
class PoolCheckConfigurationTest {

  /** Captures the check's lines. */
  private final LogLineCapture lines = new LogLineCapture(PoolCheck.class.getName());

  /** Starts capturing the check's lines. */
  @BeforeEach
  void startCapture() {
    lines.start();
  }

  /** Stops capturing the check's lines. */
  @AfterEach
  void stopCapture() {
    lines.stop();
  }

  /** Returns the application with the main pool and three named pools of 10 each: 40 in all. */
  private ApplicationContextRunner withFourPools() {
    return withBatchPool()
        .withPropertyValues(
            "stratum.data.sources.reports.url=" + URL, "stratum.data.sources.exports.url=" + URL);
  }

  /** Returns the startup failure, which must be a {@code StratumException} with the code. */
  private StratumException failureOf(AssertableApplicationContext context, DataErrorCode code) {
    var failure = NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure());
    assertThat(failure).isInstanceOf(StratumException.class);
    assertThat(((StratumException) failure).getErrorCode()).isEqualTo(code);
    return (StratumException) failure;
  }

  /** Returns every captured line's text. */
  private String loggedText() {
    return lines.lines().stream().map(ILoggingEvent::getFormattedMessage).toList().toString();
  }

  @Nested
  @DisplayName("the cap")
  class Cap {

    @Test
    @DisplayName("the main pool counts 10 behind the boundary, so four pools of 10 exceed 30")
    void countsMainBehindBoundary() {
      withFourPools()
          .run(
              context ->
                  assertThat(failureOf(context, DataErrorCode.TOO_MANY_CONNECTIONS))
                      .hasMessageContaining(DataConstants.MAX_CONNECTIONS + " is 30")
                      .hasMessageContaining("open 40 connections")
                      .hasMessageContaining("main=10"));
    }

    @Test
    @DisplayName("a higher cap lets the same pools start")
    void higherCapPasses() {
      withFourPools()
          .withPropertyValues(DataConstants.MAX_CONNECTIONS + "=40")
          .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("the main pool alone above the cap stops startup")
    void mainAloneCounts() {
      withMainPool()
          .withPropertyValues("spring.datasource.hikari.maximum-pool-size=31")
          .run(
              context ->
                  assertThat(failureOf(context, DataErrorCode.TOO_MANY_CONNECTIONS))
                      .hasMessageContaining("main=31"));
    }

    @Test
    @DisplayName(
        "a service's own HikariCP pool with no name, beside Boot's, counts under its bean name")
    void countsServicePool() {
      withBatchPool()
          .withPropertyValues("stratum.data.sources.reports.url=" + URL)
          .withBean(
              "ordersDataSource",
              HikariDataSource.class,
              this::servicePool,
              definition -> ((AbstractBeanDefinition) definition).setDefaultCandidate(false))
          .run(
              context ->
                  assertThat(failureOf(context, DataErrorCode.TOO_MANY_CONNECTIONS))
                      .hasMessageContaining("ordersDataSource=10"));
    }

    @Test
    @DisplayName("a pool two beans hand out counts once")
    void countsAliasOnce() {
      withBatchPool()
          .withPropertyValues("stratum.data.sources.reports.url=" + URL)
          .withUserConfiguration(AliasConfiguration.class)
          .run(context -> assertThat(context).hasNotFailed().hasBean("aliasDataSource"));
    }

    @Test
    @DisplayName("a data source that is no HikariCP pool counts 0, and its line says so")
    void otherDataSourceCountsZero() {
      withMainPool()
          .withBean("dataSource", DataSource.class, () -> mock(DataSource.class))
          .run(context -> assertOtherDataSourceCountsZero(context));
    }

    /** Checks what otherDataSourceCountsZero expects of the started application. */
    private void assertOtherDataSourceCountsZero(AssertableApplicationContext context) {
      assertThat(context).hasNotFailed();
      assertThat(loggedText()).contains("'dataSource' is not a HikariCP pool");
    }

    /** Returns a service's own pool, with no name. */
    private HikariDataSource servicePool() {
      var pool = new HikariDataSource();
      pool.setJdbcUrl(URL);
      return pool;
    }
  }

  @Nested
  @DisplayName("pool names")
  class Names {

    @Test
    @DisplayName(
        "two pools with one name stop startup with SX-DATA-1004, as their meters would mix")
    void rejectsSharedName() {
      withBatchPool()
          .withPropertyValues(BATCH_KEYS + "hikari.pool-name=main")
          .run(
              context ->
                  assertThat(failureOf(context, DataErrorCode.POOL_SETTING_INVALID))
                      .hasMessageContaining("two pools are named main"));
    }
  }

  @Nested
  @DisplayName("the startup lines")
  class Lines {

    @Test
    @DisplayName("each pool is logged with its name and maximum only")
    void logsEachPool() {
      withBatchPool()
          .withPropertyValues(BATCH_KEYS + "hikari.maximum-pool-size=4")
          .run(
              context ->
                  assertThat(lines.lines())
                      .extracting(ILoggingEvent::getFormattedMessage)
                      .containsExactlyInAnyOrder(
                          "Pool 'main' opens at most 10 connections",
                          "Pool 'batch' opens at most 4 connections"));
    }
  }

  @Nested
  @DisplayName("credentials")
  class Credentials {

    /** URLs that carry the password, as PostgreSQL's and Oracle's drivers accept it. */
    private static final List<String> URLS_WITH_PASSWORD =
        List.of(URL_WITH_PASSWORD, ORACLE_URL_WITH_PASSWORD);

    @ParameterizedTest(name = "{0}")
    @FieldSource("URLS_WITH_PASSWORD")
    @DisplayName("a password in the url or the key reaches no line or startup failure")
    void hidesPassword(String url) {
      withFourPools()
          .withPropertyValues(BATCH_KEYS + "url=" + url, BATCH_KEYS + "password=" + SECRET)
          .run(context -> assertHidesPassword(context));
    }

    /** Checks what hidesPassword expects of the started application. */
    private void assertHidesPassword(AssertableApplicationContext context) {
      assertThat(context).hasFailed();
      assertThat(ExceptionUtils.getStackTrace(context.getStartupFailure())).doesNotContain(SECRET);
      assertThat(loggedText()).doesNotContain(SECRET).doesNotContain("jdbc:");
    }

    @Test
    @DisplayName("the cap's message names each pool and its maximum, never its url")
    void capMessageHasNoUrl() {
      withFourPools()
          .withPropertyValues(BATCH_KEYS + "url=" + URL_WITH_PASSWORD)
          .run(
              context ->
                  assertThat(failureOf(context, DataErrorCode.TOO_MANY_CONNECTIONS))
                      .hasMessageNotContaining(SECRET)
                      .hasMessageNotContaining("jdbc:"));
    }

    @Test
    @DisplayName("the settings' toString shows that a url is set, never the credentials")
    void settingsHideCredentials() {
      var environment =
          new MockEnvironment()
              .withProperty(BATCH_KEYS + "url", URL_WITH_PASSWORD)
              .withProperty(BATCH_KEYS + "password", SECRET);

      assertThat(DataProperties.resolve(environment).toString())
          .doesNotContain(SECRET)
          .contains("url=set");
    }
  }

  /** A service bean that hands out the pool {@code batch} under a second name. */
  @Configuration(proxyBeanMethods = false)
  static class AliasConfiguration {

    /**
     * Returns the pool {@code batch} under a second name.
     *
     * @param batch the pool {@code batch}
     * @return the same pool
     */
    @Bean(defaultCandidate = false)
    DataSource aliasDataSource(@Qualifier("batchDataSource") DataSource batch) {
      return batch;
    }
  }
}
