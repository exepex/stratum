package com.exepex.stratum.data.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.data.it.pools.Note;
import com.exepex.stratum.data.it.pools.PoolsApplication;
import com.exepex.stratum.data.it.pools.batch.BatchNotes;
import com.exepex.stratum.data.it.pools.main.MainNotes;
import com.exepex.stratum.data.testsupport.PoolDatabases;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = {PoolsApplication.class, PostgresContainerConfiguration.class},
    properties = {PoolDatabases.MAPPING, PoolDatabases.SQL_INIT, PoolDatabases.SCHEMA})
@ContextConfiguration(initializers = PoolDatabases.BatchDatabase.class)
@DisplayName("On PostgreSQL, a JPA transaction stays on the pool of its first repository call")
class TransactionPoolIntegrationTest {

  private static final String MAIN = DataConstants.MAIN_POOL;

  /** The data source bean of the pool {@code batch}. */
  private static final String BATCH_DATA_SOURCE = "batchDataSource";

  @Autowired private ApplicationContext application;

  @Autowired private TransactionTemplate transactions;

  @Autowired private MainNotes mainNotes;

  @Autowired private BatchNotes batchNotes;

  @Autowired
  @Qualifier("batchTransactionManager")
  private PlatformTransactionManager batchManager;

  /** The shared entity manager, which code outside repositories uses. */
  @PersistenceContext private EntityManager entityManager;

  /** Both databases, read through their own pools. */
  private PoolDatabases databases;

  /** Empties both databases. */
  @BeforeEach
  void reset() {
    databases = new PoolDatabases(application);
    databases.reset();
  }

  /** Returns transactions of the JPA manager with the given propagation. */
  private TransactionTemplate transactionsWith(int propagation) {
    var template = new TransactionTemplate(transactions.getTransactionManager());
    template.setPropagationBehavior(propagation);
    return template;
  }

  /** Saves a batch note, then counts the notes an unmapped repository sees. */
  private long saveBatchThenCountMain() {
    batchNotes.save(new Note(null, PoolDatabases.BATCH));
    return mainNotes.count();
  }

  /** Saves a batch note, then a main note in a new transaction. */
  private void saveBatchThenMainInNewTransaction() {
    batchNotes.save(new Note(null, PoolDatabases.BATCH));
    transactionsWith(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
        .executeWithoutResult(status -> mainNotes.save(new Note(null, MAIN)));
  }

  /** Saves a main note, then a batch note. */
  private void saveMainThenBatch() {
    mainNotes.save(new Note(null, MAIN));
    batchNotes.save(new Note(null, PoolDatabases.BATCH));
  }

  /** Runs a JDBC statement on main, then saves a batch note. */
  private void selectOnMainThenSaveBatch() {
    JdbcClient.create(application.getBean(DataSource.class))
        .sql("SELECT 1")
        .query(Integer.class)
        .single();
    batchNotes.save(new Note(null, PoolDatabases.BATCH));
  }

  /** Loads a note through the entity manager, then through the batch repository. */
  private void findOnMainThenBatch(UUID id) {
    entityManager.find(Note.class, id);
    batchNotes.findById(id);
  }

  /** Saves a batch note and marks the transaction for rollback. */
  private void saveBatchAndRollBack(TransactionStatus status) {
    batchNotes.save(new Note(null, "rolled back"));
    status.setRollbackOnly();
  }

  @Nested
  @DisplayName("one pool per transaction")
  class OnePool {

    @Test
    @DisplayName("an unmapped repository called after a routed one follows the transaction's pool")
    void unmappedFollows() {
      var counted = transactions.execute(status -> saveBatchThenCountMain());

      assertThat(counted).isOne();
      assertThat(databases.batchRows()).isOne();
      assertThat(databases.mainRows()).isZero();
    }

    @Test
    @DisplayName("a new transaction inside one on batch may use main")
    void newTransactionMayUseMain() {
      transactions.executeWithoutResult(status -> saveBatchThenMainInNewTransaction());

      assertThat(databases.batchRows()).isOne();
      assertThat(databases.mainRows()).isOne();
    }
  }

  @Nested
  @DisplayName("a call for a second pool")
  class SecondPool {

    @Test
    @DisplayName(
        "after a main repository call, a batch repository call fails at once, nothing kept")
    void mainRepositoryFirst() {
      assertThatThrownBy(() -> transactions.executeWithoutResult(status -> saveMainThenBatch()))
          .hasMessageContaining(DataErrorCode.POOL_MIXED.getCode())
          .hasMessageContaining("pool main reached a repository of pool batch");

      assertThat(databases.mainRows()).isZero();
      assertThat(databases.batchRows()).isZero();
    }

    @Test
    @DisplayName("after a JDBC statement on main, a batch call fails before reaching any database")
    void jdbcStatementFirst() {
      assertThatThrownBy(
              () -> transactions.executeWithoutResult(status -> selectOnMainThenSaveBatch()))
          .hasStackTraceContaining(DataErrorCode.POOL_MIXED.getCode())
          .hasStackTraceContaining("pool main reached a repository of pool batch");

      assertThat(databases.mainRows()).isZero();
      assertThat(databases.batchRows()).isZero();
    }

    @Test
    @DisplayName("after an entity loaded through the EntityManager, a batch read of it fails")
    void entityManagerFirst() {
      var id = mainNotes.save(new Note(null, MAIN)).getId();

      assertThatThrownBy(() -> transactions.executeWithoutResult(status -> findOnMainThenBatch(id)))
          .hasMessageContaining(DataErrorCode.POOL_MIXED.getCode())
          .hasMessageContaining("pool main reached a repository of pool batch");
    }

    @Test
    @DisplayName("in a transaction of the pool's own manager, which JPA does not use, it fails")
    void poolManagerTransaction() {
      var batchTransactions = new TransactionTemplate(batchManager);

      assertThatThrownBy(
              () ->
                  batchTransactions.executeWithoutResult(
                      status -> batchNotes.save(new Note(null, PoolDatabases.BATCH))))
          .hasMessageContaining(DataErrorCode.POOL_MIXED.getCode())
          .hasMessageContaining("a repository of pool batch was called in a transaction")
          .hasMessageContaining("not JPA's");

      assertThat(databases.batchRows()).isZero();
    }
  }

  /** Saves a main note, then a batch note through a method with the given transaction rule. */
  private void saveMainThen(Consumer<Note> batchSave) {
    mainNotes.save(new Note(null, MAIN));
    batchSave.accept(new Note(null, PoolDatabases.BATCH));
  }

  /** Saves a batch note through a method with the given transaction rule, then a main note. */
  private void saveBatchThenMain(Consumer<Note> batchSave) {
    batchSave.accept(new Note(null, PoolDatabases.BATCH));
    mainNotes.save(new Note(null, MAIN));
  }

  @Nested
  @DisplayName("a repository method with a transaction rule of its own")
  class OwnTransaction {

    @Test
    @DisplayName("REQUIRES_NEW runs on its pool inside a transaction on main")
    void runsInsideMainTransaction() {
      transactions.executeWithoutResult(status -> saveMainThen(batchNotes::saveInOwnTransaction));

      assertThat(databases.mainRows()).isOne();
      assertThat(databases.batchRows()).isOne();
    }

    @Test
    @DisplayName("REQUIRES_NEW leaves the caller's transaction free to take its next call's pool")
    void leavesCallerUnpinned() {
      transactions.executeWithoutResult(
          status -> saveBatchThenMain(batchNotes::saveInOwnTransaction));

      assertThat(databases.mainRows()).isOne();
      assertThat(databases.batchRows()).isOne();
    }

    @Test
    @DisplayName("NOT_SUPPORTED runs on its pool, outside the caller's transaction on main")
    void notSupportedRunsOutside() {
      transactions.executeWithoutResult(status -> saveMainThen(batchNotes::saveWithoutTransaction));

      assertThat(databases.mainRows()).isOne();
      assertThat(databases.batchRows()).isOne();
    }

    @Test
    @DisplayName("NEVER inside a transaction gets Spring's own failure, not SX-DATA-1008")
    void neverInsideFailsAsSpringSays() {
      assertThatThrownBy(
              () ->
                  transactions.executeWithoutResult(
                      status -> saveMainThen(batchNotes::saveOutsideTransactions)))
          .isInstanceOf(IllegalTransactionStateException.class)
          .hasMessageNotContaining(DataErrorCode.POOL_MIXED.getCode());

      assertThat(databases.mainRows()).isZero();
      assertThat(databases.batchRows()).isZero();
    }

    @Test
    @DisplayName("NEVER outside a transaction runs on its pool")
    void neverOutsideRuns() {
      batchNotes.saveOutsideTransactions(new Note(null, PoolDatabases.BATCH));

      assertThat(databases.batchRows()).isOne();
      assertThat(databases.mainRows()).isZero();
    }
  }

  @Nested
  @DisplayName("a finished transaction")
  class Finished {

    @Test
    @DisplayName("leaves no pool behind after a rollback: the next unmapped write lands on main")
    void rollbackForgetsPool() {
      transactions.executeWithoutResult(TransactionPoolIntegrationTest.this::saveBatchAndRollBack);
      transactions.executeWithoutResult(status -> mainNotes.save(new Note(null, MAIN)));

      assertThat(databases.mainRows()).isOne();
      assertThat(databases.batchRows()).isZero();
    }
  }

  @Nested
  @DisplayName("connections")
  class Connections {

    @Test
    @DisplayName("a JPA transaction borrows at its first statement, from that statement's pool")
    void borrowedAtFirstStatement() {
      var active = transactions.execute(status -> activeAround(batchNotes::count));

      assertThat(active).containsExactly(List.of(0, 0), List.of(0, 1));
    }

    @Test
    @DisplayName("an unmapped repository borrows from main at its first statement")
    void mainAtFirstStatement() {
      var active = transactions.execute(status -> activeAround(mainNotes::count));

      assertThat(active).containsExactly(List.of(0, 0), List.of(1, 0));
    }

    @Test
    @DisplayName("the pool's own transaction manager still borrows when its transaction begins")
    void poolManagerBorrowsEagerly() {
      var active = new TransactionTemplate(batchManager).execute(status -> activeConnections());

      assertThat(active).containsExactly(0, 1);
    }

    /** Returns the connections lent out before and after a call. */
    private List<List<Integer>> activeAround(Runnable call) {
      var before = activeConnections();
      call.run();
      return List.of(before, activeConnections());
    }

    /** Returns the connections main and batch have lent out, in that order. */
    private List<Integer> activeConnections() {
      try {
        return List.of(
            databases.activeConnections(DataConstants.MAIN_BEAN),
            databases.activeConnections(BATCH_DATA_SOURCE));
      } catch (SQLException failure) {
        throw new IllegalStateException(failure);
      }
    }
  }

  @Nested
  @DisplayName("time limits")
  class TimeLimits {

    @Test
    @DisplayName("a routed statement ends at its transaction's limit, which SX-DATA-1003 names")
    void transactionLimit() {
      var shortTransactions = new TransactionTemplate(transactions.getTransactionManager());
      shortTransactions.setTimeout(1);

      assertThatThrownBy(
              () ->
                  shortTransactions.executeWithoutResult(status -> batchNotes.sleepThreeSeconds()))
          .hasStackTraceContaining(DataErrorCode.STATEMENT_TIMED_OUT.getCode())
          .hasStackTraceContaining("set by transaction, on pool batch");
    }
  }
}
