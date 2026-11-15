package com.exepex.stratum.data.routing;

import static com.exepex.stratum.data.testsupport.NamedPoolTestData.BATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.data.testsupport.MockDatabase;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("PoolRouting gives each call its repository's pool, and each transaction one pool")
class PoolRoutingTest {

  private static final String MAIN = DataConstants.MAIN_POOL;

  /** The pool of a second named pool. */
  private static final String REPORTING = "reporting";

  /** The repository name the messages carry. */
  private static final String REPOSITORY = "com.acme.batch.Jobs";

  private final PoolRouting routing = new PoolRouting();

  /** Real transactions, over a pool of mocks. */
  private final TransactionTemplate transactions;

  /**
   * Builds the transactions over a pool of mocks.
   *
   * @throws SQLException never; the mocks declare it
   */
  PoolRoutingTest() throws SQLException {
    transactions =
        new TransactionTemplate(new DataSourceTransactionManager(new MockDatabase().pool));
  }

  @Nested
  @DisplayName("the pool a call wants")
  class CallPool {

    @Test
    @DisplayName("is main outside any repository call and transaction")
    void mainByDefault() {
      assertThat(routing.currentPool()).isEqualTo(MAIN);
    }

    @Test
    @DisplayName("is the repository's pool while it runs, and the enclosing call's after it")
    void repositoryPoolWhileItRuns() {
      final var outer = routing.enter(BATCH);
      var inner = routing.enter(REPORTING);
      assertThat(routing.currentPool()).isEqualTo(REPORTING);

      routing.exit(inner);
      assertThat(routing.currentPool()).isEqualTo(BATCH);
      routing.exit(outer);
      assertThat(routing.currentPool()).isEqualTo(MAIN);
      assertThat(outer).isNull();
    }

    @Test
    @DisplayName("is the transaction's pool once the repository call ended")
    void transactionPoolAfterTheCall() {
      var pools =
          transactions.execute(
              status -> {
                routing.pinTransaction(BATCH, REPOSITORY);
                return routing.currentPool();
              });

      assertThat(pools).isEqualTo(BATCH);
    }
  }

  @Nested
  @DisplayName("the pool of a transaction")
  class TransactionPin {

    @Test
    @DisplayName("is the first call's pool, and a later call of that pool keeps it")
    void firstCallsPool() {
      transactions.executeWithoutResult(
          status -> {
            routing.pinTransaction(BATCH, REPOSITORY);
            routing.pinTransaction(BATCH, REPOSITORY);

            assertThat(routing.transactionPool()).isEqualTo(BATCH);
          });
    }

    @Test
    @DisplayName("stops a call of another pool with SX-DATA-1008, naming both pools")
    void anotherPoolFails() {
      transactions.executeWithoutResult(
          status -> {
            routing.pinTransaction(MAIN, "com.acme.orders.Orders");

            assertThatThrownBy(() -> routing.pinTransaction(BATCH, REPOSITORY))
                .isInstanceOf(StratumException.class)
                .hasMessageContaining(DataErrorCode.POOL_MIXED.getCode())
                .hasMessageContaining("pool main reached the repository " + REPOSITORY)
                .hasMessageContaining("of pool batch");
          });
    }

    @Test
    @DisplayName("is not set outside a transaction")
    void noneOutsideTransactions() {
      routing.pinTransaction(BATCH, REPOSITORY);

      assertThat(routing.transactionPool()).isNull();
    }

    @Test
    @DisplayName("is forgotten after a rollback, so the next transaction starts on no pool")
    void forgottenAfterRollback() {
      transactions.executeWithoutResult(
          status -> {
            routing.pinTransaction(BATCH, REPOSITORY);
            status.setRollbackOnly();
          });

      var next = transactions.execute(status -> routing.currentPool());

      assertThat(next).isEqualTo(MAIN);
      assertThat(routing.transactionPool()).isNull();
    }

    @Test
    @DisplayName("is forgotten after a commit")
    void forgottenAfterCommit() {
      transactions.executeWithoutResult(status -> routing.pinTransaction(BATCH, REPOSITORY));

      assertThat(routing.transactionPool()).isNull();
    }

    @Test
    @DisplayName("is set aside while a new transaction runs, and comes back after it")
    void setAsideByNewTransaction() {
      var inner = new TransactionTemplate(transactions.getTransactionManager());
      inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      transactions.executeWithoutResult(
          status -> {
            routing.pinTransaction(BATCH, REPOSITORY);
            inner.executeWithoutResult(
                innerStatus -> {
                  assertThat(routing.transactionPool()).isNull();
                  routing.pinTransaction(MAIN, REPOSITORY);
                });

            assertThat(routing.transactionPool()).isEqualTo(BATCH);
          });
    }
  }

  @Nested
  @DisplayName("the check of a statement")
  class StatementCheck {

    @Test
    @DisplayName("puts a transaction on no pool yet on the connection's pool, so another fails")
    void pinsTransaction() {
      transactions.executeWithoutResult(
          status -> {
            routing.checkStatement(MAIN);

            assertThat(routing.transactionPool()).isEqualTo(MAIN);
            assertThatThrownBy(() -> routing.pinTransaction(BATCH, REPOSITORY))
                .isInstanceOf(StratumException.class)
                .hasMessageContaining(DataErrorCode.POOL_MIXED.getCode());
          });
    }

    @Test
    @DisplayName("puts nothing on a pool outside a transaction")
    void noPinOutsideTransactions() {
      routing.checkStatement(MAIN);

      assertThat(routing.transactionPool()).isNull();
    }

    @Test
    @DisplayName("lets a statement through on the pool its call wants")
    void samePoolPasses() {
      var previous = routing.enter(BATCH);
      try {
        routing.checkStatement(BATCH);
      } finally {
        routing.exit(previous);
      }

      assertThat(routing.currentPool()).isEqualTo(MAIN);
    }

    @Test
    @DisplayName("stops a statement on a connection of another pool with SX-DATA-1008")
    void otherPoolFails() {
      var previous = routing.enter(BATCH);
      try {
        assertThatThrownBy(() -> routing.checkStatement(MAIN))
            .isInstanceOf(StratumException.class)
            .hasMessageContaining(DataErrorCode.POOL_MIXED.getCode())
            .hasMessageContaining("pool main reached a statement of pool batch");
      } finally {
        routing.exit(previous);
      }
    }
  }
}
