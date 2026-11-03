package com.exepex.stratum.data.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.it.orders.OrdersApplication;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.autoconfigure.JpaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

@SpringBootTest(classes = OrdersApplication.class, properties = "spring.sql.init.mode=always")
@Import(PostgresContainerConfiguration.class)
@DisplayName("A service on PostgreSQL gets Stratum's pool, Hibernate and transaction defaults")
class DataDefaultsIntegrationTest {

  /** How long Stratum lets a caller wait for a connection, in milliseconds. */
  private static final long CONNECTION_TIMEOUT_MILLIS = 3000;

  private static final String ON = "true";

  /** Hibernate's settings that Stratum gives a default, with Stratum's values. */
  private static final Map<String, String> HIBERNATE_DEFAULTS =
      Map.of(
          "hibernate.hbm2ddl.auto", "validate",
          "hibernate.query.in_clause_parameter_padding", ON,
          "hibernate.query.fail_on_pagination_over_collection_fetch", ON,
          "hibernate.jdbc.batch_size", "50",
          "hibernate.order_inserts", ON,
          "hibernate.order_updates", ON);

  @Autowired private HikariDataSource dataSource;

  @Autowired private EntityManagerFactory entityManagerFactory;

  @Autowired private JpaProperties jpaProperties;

  @Autowired private JpaTransactionManager transactionManager;

  @Test
  @DisplayName("a caller waits at most 3 seconds for a connection")
  void waitsThreeSecondsForConnection() {
    assertThat(dataSource.getConnectionTimeout()).isEqualTo(CONNECTION_TIMEOUT_MILLIS);
  }

  @Test
  @DisplayName("Hibernate validates the schema, batches ordered writes and pads IN lists")
  void startsHibernateWithDefaults() {
    var hibernateSettings = entityManagerFactory.getProperties();

    HIBERNATE_DEFAULTS.forEach(
        (key, value) ->
            assertThat(String.valueOf(hibernateSettings.get(key))).as(key).isEqualTo(value));
  }

  @Test
  @DisplayName("open-in-view is off")
  void turnsOpenInViewOff() {
    assertThat(jpaProperties.getOpenInView()).isFalse();
  }

  @Test
  @DisplayName("with Spring Batch present, transactions keep Spring's default of no time limit")
  void leavesTransactionTimeoutWithSpringBatch() {
    assertThat(transactionManager.getDefaultTimeout())
        .isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
  }
}
