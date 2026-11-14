package com.exepex.stratum.data.autoconfigure;

import static com.exepex.stratum.data.testsupport.NamedPoolContexts.withBatchPool;
import static com.exepex.stratum.data.testsupport.NamedPoolTestData.BATCH_KEYS;
import static com.exepex.stratum.data.testsupport.NamedPoolTestData.BATCH_MANAGER;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.DataConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

@DisplayName("A named pool's transaction manager is built as Spring Boot builds the main pool's")
class NamedPoolTransactionManagerTest {

  /** The seconds of the transaction limit every transaction gets by default. */
  private static final int DEFAULT_SECONDS = 60;

  /** The seconds of the pool's own transaction limit. */
  private static final int POOL_SECONDS = 5;

  /** Returns the pool {@code batch}'s transaction manager. */
  private DataSourceTransactionManager managerOf(AssertableApplicationContext context) {
    return context.getBean(BATCH_MANAGER, DataSourceTransactionManager.class);
  }

  @Nested
  @DisplayName("its kind")
  class Kind {

    @Test
    @DisplayName("it is a JdbcTransactionManager, which translates JDBC errors as Boot's does")
    void translatesErrors() {
      withBatchPool()
          .run(
              context -> assertThat(managerOf(context)).isInstanceOf(JdbcTransactionManager.class));
    }

    @Test
    @DisplayName("with exception translation off, it is a plain DataSourceTransactionManager")
    void plainWithoutTranslation() {
      withBatchPool()
          .withPropertyValues(DataConstants.EXCEPTION_TRANSLATION + "=false")
          .run(
              context ->
                  assertThat(managerOf(context))
                      .isExactlyInstanceOf(DataSourceTransactionManager.class));
    }
  }

  @Nested
  @DisplayName("its settings and time limit")
  class Timeouts {

    @Test
    @DisplayName("Spring Boot's spring.transaction.* keys reach it through Boot's customizers")
    void takesBootKeys() {
      withBatchPool()
          .withPropertyValues(
              "spring.transaction.rollback-on-commit-failure=true",
              DataConstants.TRANSACTION_TIMEOUT + "=" + DEFAULT_SECONDS + "s")
          .run(context -> assertTakesBootKeys(context));
    }

    @Test
    @DisplayName("the pool's transaction-timeout wins over spring.transaction.default-timeout")
    void poolLimitWins() {
      withBatchPool()
          .withPropertyValues(
              DataConstants.TRANSACTION_TIMEOUT + "=" + DEFAULT_SECONDS + "s",
              BATCH_KEYS + "transaction-timeout=" + POOL_SECONDS + "s")
          .run(context -> assertPoolLimitWins(context));
    }

    @Test
    @DisplayName("with no limit anywhere, it keeps Spring's own: none")
    void noLimit() {
      withBatchPool()
          .run(
              context ->
                  assertThat(managerOf(context).getDefaultTimeout())
                      .isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT));
    }
  }

  /** Checks what takesBootKeys expects of the started application. */
  private void assertTakesBootKeys(AssertableApplicationContext context) {
    assertThat(managerOf(context).isRollbackOnCommitFailure()).isTrue();
    assertThat(managerOf(context).getDefaultTimeout()).isEqualTo(DEFAULT_SECONDS);
  }

  /** Checks what poolLimitWins expects of the started application. */
  private void assertPoolLimitWins(AssertableApplicationContext context) {
    assertThat(managerOf(context).getDefaultTimeout()).isEqualTo(POOL_SECONDS);
    assertThat(
            context
                .getBean("transactionManager", DataSourceTransactionManager.class)
                .getDefaultTimeout())
        .isEqualTo(DEFAULT_SECONDS);
  }
}
