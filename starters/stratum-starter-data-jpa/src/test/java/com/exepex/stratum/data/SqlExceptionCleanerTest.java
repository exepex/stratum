package com.exepex.stratum.data;

import static com.exepex.stratum.data.testsupport.DataAssertions.assertChainCarriesNo;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.CONNECTION_FAILURE;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.CONSTRAINT;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.FOREIGN_KEY_VIOLATION;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.INVALID_TEXT;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.PERSONAL_VALUE;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.UNIQUE_VIOLATION;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.postgresCleaner;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.postgresFailure;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.testsupport.DataAssertions;
import java.io.IOException;
import java.sql.BatchUpdateException;
import java.sql.ClientInfoStatus;
import java.sql.DataTruncation;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLWarning;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("SqlExceptionCleaner builds a copy of each database error without its data values")
class SqlExceptionCleanerTest {

  private final SqlExceptionCleaner cleaner = postgresCleaner();

  @Nested
  @DisplayName("the copy's class")
  class CopyClass {

    /** A vendor code a driver could give. */
    private static final int VENDOR_CODE = 7;

    /** The column of a truncation. */
    private static final int COLUMN_INDEX = 3;

    /** The size of the value that did not fit. */
    private static final int DATA_SIZE = 40;

    /** The size that was written. */
    private static final int TRANSFER_SIZE = 20;

    @Test
    @DisplayName("a driver's subclass of SQLException becomes a plain SQLException")
    void keepsPlainSqlException() {
      var copy = cleaner.cleaned(postgresFailure(INVALID_TEXT));

      assertThat(copy.getClass()).isEqualTo(SQLException.class);
      assertThat(copy.getSQLState()).isEqualTo(INVALID_TEXT);
    }

    @Test
    @DisplayName("a standard subclass stays that subclass")
    void keepsStandardSubclass() {
      var copy =
          cleaner.cleaned(new SQLTimeoutException("slow " + PERSONAL_VALUE, "57014", VENDOR_CODE));

      assertThat(copy.getClass()).isEqualTo(SQLTimeoutException.class);
      assertThat(copy.getErrorCode()).isEqualTo(VENDOR_CODE);
    }

    @Test
    @DisplayName("a batch error keeps its update counts")
    void keepsBatchUpdateCounts() {
      var counts = new long[] {1, 1, java.sql.Statement.EXECUTE_FAILED};
      var copy =
          (BatchUpdateException)
              cleaner.cleaned(
                  new BatchUpdateException(PERSONAL_VALUE, UNIQUE_VIOLATION, 0, counts, null));

      assertThat(copy.getLargeUpdateCounts()).containsExactly(counts);
      assertThat(copy.getUpdateCounts()).containsExactly(1, 1, java.sql.Statement.EXECUTE_FAILED);
      assertThat(copy.getMessage()).isEqualTo("SQLState 23505, vendor code 0");
    }

    @Test
    @DisplayName("a client-info error keeps the names of the properties not set")
    void keepsFailedClientInfoProperties() {
      var failed = Map.of("ApplicationName", ClientInfoStatus.REASON_VALUE_INVALID);
      var copy =
          (SQLClientInfoException)
              cleaner.cleaned(new SQLClientInfoException(PERSONAL_VALUE, failed));

      assertThat(copy.getFailedProperties()).isEqualTo(failed);
    }

    @Test
    @DisplayName("a data truncation keeps its sizes and the JDK's own message")
    void keepsDataTruncation() {
      var copy =
          (DataTruncation)
              cleaner.cleaned(
                  new DataTruncation(COLUMN_INDEX, true, false, DATA_SIZE, TRANSFER_SIZE));

      assertThat(copy.getIndex()).isEqualTo(COLUMN_INDEX);
      assertThat(copy.getDataSize()).isEqualTo(DATA_SIZE);
      assertThat(copy.getTransferSize()).isEqualTo(TRANSFER_SIZE);
      assertThat(copy.getMessage()).isEqualTo("Data truncation");
    }

    @Test
    @DisplayName("a warning stays a warning")
    void keepsWarning() {
      var copy = cleaner.cleaned(new SQLWarning("NOTICE: " + PERSONAL_VALUE, "00000"));

      assertThat((Throwable) copy)
          .isExactlyInstanceOf(SQLWarning.class)
          .hasMessage("SQLState 00000, vendor code 0");
    }
  }

  @Nested
  @DisplayName("the copy's message")
  class Message {

    /** SQLStates of errors about the system, one of each class whose first line is kept. */
    private static final List<String> SYSTEM_STATES =
        List.of(CONNECTION_FAILURE, "28P01", "40P01", "53300", "57014", "58030");

    @ParameterizedTest(name = "{0}")
    @FieldSource("SYSTEM_STATES")
    @DisplayName("an error about the system keeps the first line and drops the rest")
    void keepsFirstLineOfSystemErrors(String state) {
      var copy =
          cleaner.cleaned(
              new SQLException("the system failed\n  Detail: " + PERSONAL_VALUE, state));

      assertThat((Throwable) copy).hasMessage("the system failed");
    }

    @Test
    @DisplayName("a batch error about the system drops its message, which holds the statement")
    void dropsBatchMessageOfSystemError() {
      var batchFailure =
          new BatchUpdateException(
              "Batch entry 0 INSERT INTO customers VALUES ('" + PERSONAL_VALUE + "') was aborted",
              "40P01",
              0,
              new long[] {java.sql.Statement.EXECUTE_FAILED},
              null);

      assertThat((Throwable) cleaner.cleaned(batchFailure))
          .hasMessage("SQLState 40P01, vendor code 0");
    }

    @Test
    @DisplayName("the pool's timeout keeps its message, though it has no SQLState")
    void keepsPoolTimeoutMessage() {
      var poolMessage =
          "HikariPool-1 - Connection is not available, request timed out after 3000ms";
      var copy = cleaner.cleaned(new SQLTransientConnectionException(poolMessage));

      assertThat((Throwable) copy)
          .isExactlyInstanceOf(SQLTransientConnectionException.class)
          .hasMessage(poolMessage);
    }

    @Test
    @DisplayName("any other error gets a fixed text naming the SQLState and the vendor code")
    void dropsMessageOfDataErrors() {
      assertThat((Throwable) cleaner.cleaned(postgresFailure(INVALID_TEXT)))
          .hasMessage("SQLState 22P02, vendor code 0");
    }

    @Test
    @DisplayName("an error without a SQLState says so in words, never null")
    void namesMissingState() {
      assertThat((Throwable) cleaner.cleaned(new SQLException(PERSONAL_VALUE)))
          .hasMessage("SQLState unknown, vendor code 0");
    }

    @Test
    @DisplayName("an error about the system without a message gets the fixed text")
    void fixesMissingSystemMessage() {
      assertThat((Throwable) cleaner.cleaned(new SQLException(null, CONNECTION_FAILURE)))
          .hasMessage("SQLState 08006, vendor code 0");
    }

    @Test
    @DisplayName("a constraint violation gets the reader's message, which names the constraint")
    void usesReaderMessage() {
      assertThat((Throwable) cleaner.cleaned(postgresFailure(UNIQUE_VIOLATION)))
          .hasMessage("duplicate key value violates unique constraint \"" + CONSTRAINT + "\"");
    }

    @Test
    @DisplayName("without a reader, a constraint violation gets the fixed text")
    void fixesWithoutReader() {
      assertThat(
              (Throwable)
                  new SqlExceptionCleaner(List.of()).cleaned(postgresFailure(UNIQUE_VIOLATION)))
          .hasMessage("SQLState 23505, vendor code 0");
    }
  }

  @Nested
  @DisplayName("the copy's chain")
  class Chain {

    /** The next exceptions of a large failed batch. */
    private static final int LONG_CHAIN = 5_000;

    /** How many times longer the second chain is than the first. */
    private static final int LENGTH_FACTOR = 4;

    /**
     * The most the cleaning time may grow when the chain grows by {@link #LENGTH_FACTOR}: about 4
     * when it is linear, about 16 when it is quadratic.
     */
    private static final long GROWTH_LIMIT = 8;

    @Test
    @DisplayName("the stack trace, causes, suppressed and next exceptions are cleaned too")
    void cleansWholeChain() {
      var failure = postgresFailure(UNIQUE_VIOLATION);
      failure.initCause(new IOException("socket said " + PERSONAL_VALUE));
      failure.addSuppressed(postgresFailure(INVALID_TEXT));
      failure.setNextException(postgresFailure(FOREIGN_KEY_VIOLATION));

      var copy = cleaner.cleaned(failure);

      assertChainCarriesNo(copy, PERSONAL_VALUE);
      assertThat(copy.getStackTrace()).isEqualTo(failure.getStackTrace());
      assertThat(copy.getCause())
          .isExactlyInstanceOf(Exception.class)
          .hasMessage("java.io.IOException");
      assertThat(copy.getSuppressed())
          .singleElement()
          .hasFieldOrPropertyWithValue("SQLState", INVALID_TEXT);
      assertThat(copy.getNextException().getSQLState()).isEqualTo(FOREIGN_KEY_VIOLATION);
    }

    @Test
    @DisplayName("a long chain of next exceptions, as a large batch has, is cleaned in linear time")
    void cleansLongChainInLinearTime() {
      // A warm-up first, so that the two timed runs compare compiled code.
      nanosToClean(LONG_CHAIN);
      var shortChain = nanosToClean(LONG_CHAIN);
      var longChain = nanosToClean(LONG_CHAIN * LENGTH_FACTOR);

      assertThat(longChain).isLessThan(shortChain * GROWTH_LIMIT);
    }

    /** Returns how long the cleaner takes for a chain of the given length, checking its copy. */
    private long nanosToClean(int length) {
      var failures =
          IntStream.range(0, length).mapToObj(index -> postgresFailure(UNIQUE_VIOLATION)).toList();
      IntStream.range(1, length)
          .forEach(index -> failures.get(index - 1).setNextException(failures.get(index)));
      var start = System.nanoTime();
      var copy = cleaner.cleaned(failures.get(0));
      var elapsed = System.nanoTime() - start;
      assertThat(DataAssertions.chainOf(copy)).hasSize(length);
      return elapsed;
    }

    @Test
    @DisplayName("a copy that reaches a second boundary comes back unchanged")
    void returnsCopyUnchanged() {
      var copy = cleaner.cleaned(postgresFailure(UNIQUE_VIOLATION));

      assertThat((Throwable) cleaner.cleaned(copy)).isSameAs(copy);
    }

    @Test
    @DisplayName("a pool error carrying a copy as cause and next exception ends its chain")
    void attachesCleanTailOnce() {
      var inner = cleaner.cleaned(postgresFailure(CONNECTION_FAILURE));
      var poolFailure =
          new SQLTransientConnectionException("HikariPool-1 - timed out", null, 0, inner);
      poolFailure.setNextException(inner);

      var copy = cleaner.cleaned(poolFailure);

      assertThat(copy.getCause()).isSameAs(inner);
      assertThat((Throwable) copy.getNextException()).isSameAs(inner);
      assertThat(DataAssertions.chainOf(copy)).hasSize(2);
      assertThat((Throwable) inner.getNextException()).isNull();
    }

    @Test
    @DisplayName("a raw error met as both cause and next exception is copied once")
    void copiesSharedFailureOnce() {
      var inner = postgresFailure(CONNECTION_FAILURE);
      var poolFailure =
          new SQLTransientConnectionException("HikariPool-1 - timed out", null, 0, inner);
      poolFailure.setNextException(inner);

      var copy = cleaner.cleaned(poolFailure);

      assertThat((Throwable) copy.getNextException()).isSameAs(copy.getCause());
      assertThat(DataAssertions.chainOf(copy)).hasSize(2);
    }

    @Test
    @DisplayName("a chain of next exceptions that loops back ends instead of looping")
    void endsLoopingChain() {
      var first = postgresFailure(UNIQUE_VIOLATION);
      var second = postgresFailure(FOREIGN_KEY_VIOLATION);
      first.setNextException(second);
      second.setNextException(first);

      var copy = cleaner.cleaned(first);

      assertThat(DataAssertions.chainOf(copy)).hasSize(2);
      assertThat((Throwable) copy.getNextException().getNextException()).isNull();
    }

    @Test
    @DisplayName("a chain of next exceptions that loops back below the top ends instead of looping")
    void endsLoopBelowTop() {
      var top = postgresFailure(UNIQUE_VIOLATION);
      var first = postgresFailure(FOREIGN_KEY_VIOLATION);
      var second = postgresFailure(INVALID_TEXT);
      top.setNextException(first);
      first.setNextException(second);
      second.setNextException(first);

      var copy = cleaner.cleaned(top);

      assertThat(DataAssertions.chainOf(copy))
          .containsExactly(
              copy, copy.getNextException(), copy.getNextException().getNextException());
      assertThat((Throwable) copy.getNextException().getNextException().getNextException())
          .isNull();
    }

    @Test
    @DisplayName("a cause chain that loops back ends instead of recursing")
    void endsLoopingCauses() {
      var outer = new IllegalStateException(PERSONAL_VALUE);
      var inner = new IllegalArgumentException(PERSONAL_VALUE, outer);
      outer.initCause(inner);
      var failure = new SQLException("x", "22000", 0, outer);

      var copy = cleaner.cleaned(failure);

      assertChainCarriesNo(copy, PERSONAL_VALUE);
      assertThat(copy.getCause().getCause().getCause()).isNull();
    }
  }
}
