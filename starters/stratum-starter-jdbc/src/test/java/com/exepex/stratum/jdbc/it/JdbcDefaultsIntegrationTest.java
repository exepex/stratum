package com.exepex.stratum.jdbc.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.data.DataProperties;
import com.exepex.stratum.data.DatabaseTlsCheck;
import com.exepex.stratum.data.JdbcWrapper;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@SpringBootTest(classes = JdbcApplication.class)
@Import(PostgresContainerConfiguration.class)
@DisplayName("A plain JDBC service on PostgreSQL gets the same pool and transaction defaults")
class JdbcDefaultsIntegrationTest {

  /** How long Stratum lets a caller wait for a connection, in milliseconds. */
  private static final long CONNECTION_TIMEOUT_MILLIS = 3000;

  /** Stratum's transaction time limit, in seconds. */
  private static final int TRANSACTION_TIMEOUT_SECONDS = 60;

  /** Stratum's statement time limit. */
  private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(30);

  /** The login limit the PostgreSQL driver gets from the 3 seconds' wait, in seconds. */
  private static final String LOGIN_TIMEOUT_SECONDS = "3";

  @Autowired private ApplicationContext context;

  @Autowired private ConfigurableEnvironment environment;

  @Autowired private DataSource dataSource;

  @Autowired private DataSourceTransactionManager transactionManager;

  @Autowired private JdbcTemplate jdbcTemplate;

  /** Returns Spring Boot's pool behind the boundary. */
  private HikariDataSource pool() throws SQLException {
    return dataSource.unwrap(HikariDataSource.class);
  }

  @Test
  @DisplayName("the data source is behind the JDBC boundary")
  void wrapsDataSource() {
    assertThat(dataSource).isInstanceOf(JdbcWrapper.class);
  }

  @Test
  @DisplayName("a caller waits at most 3 seconds for a connection")
  void waitsThreeSecondsForConnection() throws SQLException {
    assertThat(pool().getConnectionTimeout()).isEqualTo(CONNECTION_TIMEOUT_MILLIS);
  }

  @Test
  @DisplayName("the PostgreSQL driver gets a login limit of 3 seconds")
  void limitsLogin() throws SQLException {
    assertThat(pool().getDataSourceProperties())
        .containsEntry("loginTimeout", LOGIN_TIMEOUT_SECONDS);
  }

  @Test
  @DisplayName("every transaction has 60 seconds")
  void limitsTransactions() {
    assertThat(transactionManager.getDefaultTimeout()).isEqualTo(TRANSACTION_TIMEOUT_SECONDS);
  }

  @Test
  @DisplayName("every statement has 30 seconds, and one past it is answered with 503")
  void limitsStatements() {
    assertThat(DataProperties.resolve(environment).queryTimeout()).isEqualTo(QUERY_TIMEOUT);
    assertThat(
            environment.getProperty(
                StratumModuleConstants.mapEntry(
                    DataConstants.WEB_ERROR_STATUSES, DataErrorCode.STATEMENT_TIMED_OUT.getCode())))
        .isEqualTo(String.valueOf(DataConstants.STATEMENT_TIMED_OUT_STATUS));
  }

  @Test
  @DisplayName("the TLS check of the data source is on")
  void checksTls() {
    assertThat(context.getBean(DataConstants.TLS_CHECK)).isInstanceOf(DatabaseTlsCheck.class);
  }

  @Test
  @DisplayName("JdbcTemplate keeps Spring Boot's fetch size, which sets none")
  void setsNoFetchSize() {
    assertThat(jdbcTemplate.getFetchSize()).isEqualTo(-1);
  }

  @Test
  @DisplayName("Stratum gives no spring.jpa key a default")
  void setsNoJpaKey() {
    var defaults =
        (EnumerablePropertySource<?>)
            environment.getPropertySources().get(DefaultPropertiesPropertySource.NAME);

    assertThat(defaults).isNotNull();
    assertThat(Arrays.asList(defaults.getPropertyNames()))
        .contains(DataConstants.CONNECTION_TIMEOUT)
        .noneMatch(name -> name.startsWith("spring.jpa."));
  }
}
