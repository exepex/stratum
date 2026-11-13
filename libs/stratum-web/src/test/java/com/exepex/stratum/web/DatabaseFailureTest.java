package com.exepex.stratum.web;

import static com.exepex.stratum.web.testsupport.DatabaseFailureTestData.duplicateKey;
import static com.exepex.stratum.web.testsupport.DatabaseFailureTestData.sqlFailure;
import static com.exepex.stratum.web.testsupport.DatabaseFailureTestData.wrapped;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.OptimisticLockException;
import java.io.Serial;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import org.hibernate.NonUniqueObjectException;
import org.hibernate.StaleObjectStateException;
import org.hibernate.StaleStateException;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

@DisplayName("DatabaseFailure recognises a busy database and a data conflict among the causes")
class DatabaseFailureTest {

  @Nested
  @DisplayName("a busy database")
  class Busy {

    /** Failures of a database that is busy or too slow, as Spring and the drivers raise them. */
    private static final List<Throwable> BUSY_FAILURES =
        List.of(
            new SQLTimeoutException("cancelled by the driver"),
            new QueryTimeoutException("statement timeout", sqlFailure("57014")),
            new SQLTransientConnectionException("HikariPool-1 - Connection is not available"),
            new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction",
                new JDBCConnectionException("pool", new SQLTransientConnectionException())),
            new CannotAcquireLockException("lock timeout", sqlFailure("55P03")),
            new CannotAcquireLockException("deadlock", sqlFailure("40P01")),
            new TransactionTimedOutException("Transaction timed out: deadline was now"));

    @ParameterizedTest
    @FieldSource("BUSY_FAILURES")
    @DisplayName("is a timeout, a pool wait, a lock timeout, a deadlock or a transaction too long")
    void busy(Throwable failure) {
      assertThat(DatabaseFailure.of(failure)).hasValue(DatabaseFailure.BUSY);
    }

    @Test
    @DisplayName("answers 503 with SX-WEB-1010 by default")
    void codeAndStatus() {
      assertThat(DatabaseFailure.BUSY.getCode()).isEqualTo(WebErrorCode.DATABASE_BUSY);
      assertThat(DatabaseFailure.BUSY.getDefaultStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
  }

  @Nested
  @DisplayName("a data conflict")
  class Conflict {

    /** Failures of a request that conflicts with the stored data. */
    private static final List<Throwable> CONFLICT_FAILURES =
        List.of(
            new OptimisticLockingFailureException("row changed"),
            new OptimisticLockingFailureException("row changed") {
              @Serial private static final long serialVersionUID = 1L;
            },
            new OptimisticLockException("raw flush", new StaleObjectStateException("Order", 42)),
            new StaleStateException("batch update returned unexpected row count"),
            duplicateKey(),
            new BatchUpdateException("Batch entry 0 was aborted", "23505", 0, new int[0]));

    @ParameterizedTest
    @FieldSource("CONFLICT_FAILURES")
    @DisplayName(
        "is a row changed meanwhile, also as a subclass or from a raw flush, or a duplicate")
    void conflict(Throwable failure) {
      assertThat(DatabaseFailure.of(failure)).hasValue(DatabaseFailure.CONFLICT);
    }

    @Test
    @DisplayName("answers 409 with SX-WEB-1011 by default")
    void codeAndStatus() {
      assertThat(DatabaseFailure.CONFLICT.getCode()).isEqualTo(WebErrorCode.DATA_CONFLICT);
      assertThat(DatabaseFailure.CONFLICT.getDefaultStatus()).isEqualTo(HttpStatus.CONFLICT);
    }
  }

  @Nested
  @DisplayName("any other failure")
  class Other {

    /**
     * Failures that keep 500: other integrity errors, a generic duplicate, a serialization failure,
     * a lost connection, an error with no SQLState, a session bug that Spring calls a duplicate
     * key, and a class named like Spring's in another package.
     */
    private static final List<Throwable> OTHER_FAILURES =
        List.of(
            sqlFailure("23503"),
            sqlFailure("23502"),
            sqlFailure("23514"),
            sqlFailure("23000"),
            sqlFailure("40001"),
            sqlFailure("08006"),
            new SQLException("no state"),
            new DuplicateKeyException("session", new NonUniqueObjectException(42, "Order")),
            new IllegalStateException("broken"),
            new LookalikeFailure.OptimisticLockingFailureException());

    @ParameterizedTest
    @FieldSource("OTHER_FAILURES")
    @DisplayName("is not a database failure that gets its own status")
    void other(Throwable failure) {
      assertThat(DatabaseFailure.of(failure)).isEmpty();
    }

    @Test
    @DisplayName("is nothing when there is no failure")
    void noFailure() {
      assertThat(DatabaseFailure.of(null)).isEmpty();
    }
  }

  @Nested
  @DisplayName("the chain of causes")
  class Chain {

    /** Wrappers above the database error, more than a real failure has. */
    private static final int WRAPPERS = 50;

    @Test
    @DisplayName("is searched to its deepest cause")
    void deepCause() {
      assertThat(DatabaseFailure.of(wrapped(WRAPPERS, sqlFailure("23505"))))
          .hasValue(DatabaseFailure.CONFLICT);
    }

    @Test
    @DisplayName("gives the kind of the outermost known failure")
    void outermostWins() {
      var failure = new OptimisticLockingFailureException("changed", new SQLTimeoutException());

      assertThat(DatabaseFailure.of(failure)).hasValue(DatabaseFailure.CONFLICT);
    }

    @Test
    @Timeout(5)
    @DisplayName("is followed once when it loops back on itself")
    void causesThatLoop() {
      var first = new IllegalStateException("first");
      var second = new IllegalArgumentException("second", first);
      first.initCause(second);

      assertThat(DatabaseFailure.of(first)).isEmpty();
    }
  }

  @Test
  @DisplayName("each class name is the real name of Spring's or Hibernate's exception")
  void namesMatchRealClasses() {
    assertThat(WebConstants.TRANSACTION_TIMED_OUT)
        .isEqualTo(TransactionTimedOutException.class.getName());
    assertThat(WebConstants.OPTIMISTIC_LOCK_FAILURE)
        .isEqualTo(OptimisticLockingFailureException.class.getName());
    assertThat(WebConstants.STALE_STATE).isEqualTo(StaleStateException.class.getName());
  }

  /** Holds a service's own exception that shares the simple name of Spring's. */
  private static final class LookalikeFailure {

    /** A service's own exception, in another package than Spring's of the same simple name. */
    private static final class OptimisticLockingFailureException extends RuntimeException {

      @Serial private static final long serialVersionUID = 1L;
    }
  }
}
