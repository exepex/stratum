package com.exepex.stratum.data.routing;

import static com.exepex.stratum.data.testsupport.NamedPoolTestData.BATCH;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.data.testsupport.MockDatabase;
import java.sql.SQLException;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
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

  private final PoolRouting routing = new PoolRouting();

  /** Real transactions, over a pool of mocks. */
  private final TransactionTemplate transactions;

  /** Transactions that suspend the one they run in. */
  private final TransactionTemplate newTransactions;

  /**
   * Builds the transactions over a pool of mocks.
   *
   * @throws SQLException never; the mocks declare it
   */
  PoolRoutingTest() throws SQLException {
    var manager = new DataSourceTransactionManager(new MockDatabase().pool);
    transactions = new TransactionTemplate(manager);
    newTransactions = new TransactionTemplate(manager);
    newTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Runs work in a transaction and returns its result. */
  private <T> @Nullable T inTransaction(Supplier<T> work) {
    return transactions.execute(status -> work.get());
  }

  /** Runs work in a transaction that is rolled back. */
  private void inRolledBackTransaction(Runnable work) {
    transactions.executeWithoutResult(status -> rollBackAfter(status::setRollbackOnly, work));
  }

  /** Runs work, then marks its transaction for rollback. */
  private void rollBackAfter(Runnable markRollback, Runnable work) {
    work.run();
    markRollback.run();
  }

  /** Puts the transaction on batch and returns the pool the current call wants. */
  private String pinBatchAndReadCurrent() {
    routing.pinTransaction(BATCH);
    return routing.currentPool();
  }

  /** Pins batch twice and returns the transaction's pool. */
  private @Nullable String pinBatchTwice() {
    routing.pinTransaction(BATCH);
    routing.pinTransaction(BATCH);
    return routing.transactionPool();
  }

  /** Pins main, then returns the failure of pinning batch. */
  private Throwable pinMainThenBatch() {
    routing.pinTransaction(MAIN);
    return failureOf(() -> routing.pinTransaction(BATCH));
  }

  /** Runs a statement on main, then returns the transaction's pool and the failure of a pin. */
  private Object[] statementOnMainThenPinBatch() {
    routing.checkStatement(MAIN);
    return new Object[] {routing.transactionPool(), failureOf(() -> routing.pinTransaction(BATCH))};
  }

  /** Pins batch, runs a new transaction that pins main, and returns the pools seen. */
  private String[] pinAcrossNewTransaction() {
    routing.pinTransaction(BATCH);
    var inner = newTransactions.execute(status -> pinMainAndReadBefore());
    return new String[] {String.valueOf(inner), String.valueOf(routing.transactionPool())};
  }

  /** Returns the pool a new transaction is on before it pins main. */
  private @Nullable String pinMainAndReadBefore() {
    var before = routing.transactionPool();
    routing.pinTransaction(MAIN);
    return before;
  }

  /** Returns what a call throws, or fails the test if it throws nothing. */
  private Throwable failureOf(Runnable call) {
    try {
      call.run();
    } catch (StratumException failure) {
      return failure;
    }
    throw new AssertionError("the call did not fail");
  }

  /** Runs work while a repository of a pool runs. */
  private void inCallOf(String pool, Runnable work) {
    var previous = routing.enter(pool);
    try {
      work.run();
    } finally {
      routing.exit(previous);
    }
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
      assertThat(inTransaction(PoolRoutingTest.this::pinBatchAndReadCurrent)).isEqualTo(BATCH);
    }
  }

  @Nested
  @DisplayName("the pool of a transaction")
  class TransactionPin {

    @Test
    @DisplayName("is the first call's pool, and a later call of that pool keeps it")
    void firstCallsPool() {
      assertThat(inTransaction(PoolRoutingTest.this::pinBatchTwice)).isEqualTo(BATCH);
    }

    @Test
    @DisplayName("stops a call of another pool with SX-DATA-1008, naming both pools and no class")
    void anotherPoolFails() {
      assertThat(inTransaction(PoolRoutingTest.this::pinMainThenBatch))
          .isInstanceOf(StratumException.class)
          .hasMessageContaining(DataErrorCode.POOL_MIXED.getCode())
          .hasMessageContaining("pool main reached a repository of pool batch");
    }

    @Test
    @DisplayName("is not set outside a transaction")
    void noneOutsideTransactions() {
      routing.pinTransaction(BATCH);

      assertThat(routing.transactionPool()).isNull();
    }

    @Test
    @DisplayName("is forgotten after a rollback, so the next transaction starts on no pool")
    void forgottenAfterRollback() {
      inRolledBackTransaction(() -> routing.pinTransaction(BATCH));

      assertThat(inTransaction(routing::currentPool)).isEqualTo(MAIN);
      assertThat(routing.transactionPool()).isNull();
    }

    @Test
    @DisplayName("is forgotten after a commit")
    void forgottenAfterCommit() {
      transactions.executeWithoutResult(status -> routing.pinTransaction(BATCH));

      assertThat(routing.transactionPool()).isNull();
    }

    @Test
    @DisplayName("is set aside while a new transaction runs, and comes back after it")
    void setAsideByNewTransaction() {
      assertThat(inTransaction(PoolRoutingTest.this::pinAcrossNewTransaction))
          .containsExactly("null", BATCH);
    }
  }

  @Nested
  @DisplayName("the check of a statement")
  class StatementCheck {

    @Test
    @DisplayName("puts a transaction on no pool yet on the connection's pool, so another fails")
    void pinsTransaction() {
      var seen = inTransaction(PoolRoutingTest.this::statementOnMainThenPinBatch);

      assertThat(seen[0]).isEqualTo(MAIN);
      assertThat((Throwable) seen[1]).hasMessageContaining(DataErrorCode.POOL_MIXED.getCode());
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
      inCallOf(BATCH, () -> routing.checkStatement(BATCH));

      assertThat(routing.currentPool()).isEqualTo(MAIN);
    }

    @Test
    @DisplayName("stops a statement on a connection of another pool with SX-DATA-1008")
    void otherPoolFails() {
      var failure = new Throwable[1];
      inCallOf(BATCH, () -> failure[0] = failureOf(() -> routing.checkStatement(MAIN)));

      assertThat(failure[0])
          .hasMessageContaining(DataErrorCode.POOL_MIXED.getCode())
          .hasMessageContaining("pool main reached a statement of pool batch");
    }
  }
}
