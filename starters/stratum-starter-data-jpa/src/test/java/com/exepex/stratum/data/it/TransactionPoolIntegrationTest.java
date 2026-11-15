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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = {PoolsApplication.class, PostgresContainerConfiguration.class},
    properties = {PoolDatabases.MAPPING, PoolDatabases.SQL_INIT, PoolDatabases.SCHEMA})
@ContextConfiguration(initializers = PoolDatabases.BatchDatabase.class)
@DisplayName("On PostgreSQL, a JPA transaction stays on the pool of its first repository call")
class TransactionPoolIntegrationTest {

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

  @Nested
  @DisplayName("one pool per transaction")
  class OnePool {

    @Test
    @DisplayName("an unmapped repository called after a routed one follows the transaction's pool")
    void unmappedFollows() {
      var counted =
          transactions.execute(
              status -> {
                batchNotes.save(new Note(null, PoolDatabases.BATCH));
                return mainNotes.count();
              });

      assertThat(counted).isOne();
      assertThat(databases.batchRows()).isOne();
      assertThat(databases.mainRows()).isZero();
    }

    @Test
    @DisplayName("a new transaction inside one on batch may use main")
    void newTransactionMayUseMain() {
      var inner = transactionsWith(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      transactions.executeWithoutResult(
          status -> {
            batchNotes.save(new Note(null, PoolDatabases.BATCH));
            inner.executeWithoutResult(
                innerStatus -> mainNotes.save(new Note(null, DataConstants.MAIN_POOL)));
          });

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
      assertThatThrownBy(
              () ->
                  transactions.executeWithoutResult(
                      status -> {
                        mainNotes.save(new Note(null, DataConstants.MAIN_POOL));
                        batchNotes.save(new Note(null, PoolDatabases.BATCH));
                      }))
          .hasMessageContaining(DataErrorCode.POOL_MIXED.getCode())
          .hasMessageContaining("of pool batch");

      assertThat(databases.mainRows()).isZero();
      assertThat(databases.batchRows()).isZero();
    }

    @Test
    @DisplayName("after a JDBC statement on main, a batch call fails before reaching any database")
    void jdbcStatementFirst() {
      var jdbc = JdbcClient.create(application.getBean(DataSource.class));

      assertThatThrownBy(
              () ->
                  transactions.executeWithoutResult(
                      status -> {
                        jdbc.sql("SELECT 1").query(Integer.class).single();
                        batchNotes.save(new Note(null, PoolDatabases.BATCH));
                      }))
          .hasStackTraceContaining(DataErrorCode.POOL_MIXED.getCode())
          .hasStackTraceContaining("pool main reached the repository");

      assertThat(databases.mainRows()).isZero();
      assertThat(databases.batchRows()).isZero();
    }

    @Test
    @DisplayName("after an entity loaded through the EntityManager, a batch read of it fails")
    void entityManagerFirst() {
      var id = mainNotes.save(new Note(null, DataConstants.MAIN_POOL)).getId();

      assertThatThrownBy(
              () ->
                  transactions.executeWithoutResult(
                      status -> {
                        entityManager.find(Note.class, id);
                        batchNotes.findById(id);
                      }))
          .hasMessageContaining(DataErrorCode.POOL_MIXED.getCode())
          .hasMessageContaining("pool main reached the repository");
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
          .hasMessageContaining("not JPA's");

      assertThat(databases.batchRows()).isZero();
    }
  }

  @Nested
  @DisplayName("a repository method with a transaction of its own")
  class OwnTransaction {

    @Test
    @DisplayName("runs on its pool inside a transaction on main")
    void runsInsideMainTransaction() {
      transactions.executeWithoutResult(
          status -> {
            mainNotes.save(new Note(null, DataConstants.MAIN_POOL));
            batchNotes.saveInOwnTransaction(new Note(null, PoolDatabases.BATCH));
          });

      assertThat(databases.mainRows()).isOne();
      assertThat(databases.batchRows()).isOne();
    }

    @Test
    @DisplayName("leaves the caller's transaction on no pool, so its next call picks its own")
    void leavesCallerUnpinned() {
      transactions.executeWithoutResult(
          status -> {
            batchNotes.saveInOwnTransaction(new Note(null, PoolDatabases.BATCH));
            mainNotes.save(new Note(null, DataConstants.MAIN_POOL));
          });

      assertThat(databases.mainRows()).isOne();
      assertThat(databases.batchRows()).isOne();
    }
  }

  @Nested
  @DisplayName("a finished transaction")
  class Finished {

    @Test
    @DisplayName("leaves no pool behind after a rollback: the next unmapped write lands on main")
    void rollbackForgetsPool() {
      transactions.executeWithoutResult(
          status -> {
            batchNotes.save(new Note(null, "rolled back"));
            status.setRollbackOnly();
          });
      transactions.executeWithoutResult(
          status -> mainNotes.save(new Note(null, DataConstants.MAIN_POOL)));

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
      var active =
          transactions.execute(
              status -> {
                var before = activeConnections();
                batchNotes.count();
                return List.of(before, activeConnections());
              });

      assertThat(active).containsExactly(List.of(0, 0), List.of(0, 1));
    }

    @Test
    @DisplayName("an unmapped repository borrows from main at its first statement")
    void mainAtFirstStatement() {
      var active =
          transactions.execute(
              status -> {
                var before = activeConnections();
                mainNotes.count();
                return List.of(before, activeConnections());
              });

      assertThat(active).containsExactly(List.of(0, 0), List.of(1, 0));
    }

    @Test
    @DisplayName("the pool's own transaction manager still borrows when its transaction begins")
    void poolManagerBorrowsEagerly() {
      var active = new TransactionTemplate(batchManager).execute(status -> activeConnections());

      assertThat(active).containsExactly(0, 1);
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
