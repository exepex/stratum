package com.exepex.stratum.test;

import static com.exepex.stratum.test.DatabaseFailureAssertions.PERSONAL_VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@DisplayName("DatabaseFailureAssertions follows a failure's whole chain")
class DatabaseFailureAssertionsTest {

  /** A clean failure, as the service sees it, with no personal value. */
  private static final String CLEAN = "SQLState 23505, vendor code 0";

  /** Returns a clean failure whose next exception, deep in its chain, carries the value. */
  private static Exception failureWithValueInNextException() {
    var database = new SQLException(CLEAN, "23505");
    database.setNextException(new SQLException("Key (email)=(" + PERSONAL_VALUE + ")"));
    return new IllegalStateException("could not save", database);
  }

  @Nested
  @DisplayName("chainOf")
  class ChainOf {

    @Test
    @DisplayName("lists the failure, its suppressed, cause and next exceptions, each once")
    void listsEachMemberOnce() {
      var next = new SQLException("next");
      var database = new SQLException(CLEAN);
      database.setNextException(next);
      var suppressed = new IllegalArgumentException("suppressed");
      var failure = new IllegalStateException("top", database);
      failure.addSuppressed(suppressed);
      database.initCause(failure);

      assertThat(DatabaseFailureAssertions.chainOf(failure))
          .containsExactly(failure, suppressed, database, next);
    }
  }

  @Nested
  @DisplayName("assertChainCarriesNo")
  class ChainCarriesNo {

    @Test
    @DisplayName("fails when only a next exception deep in the chain carries the value")
    void failsOnValueInNextException() {
      assertThatThrownBy(
              () ->
                  DatabaseFailureAssertions.assertChainCarriesNo(
                      failureWithValueInNextException(), PERSONAL_VALUE))
          .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("fails when only a suppressed exception carries the value")
    void failsOnValueInSuppressed() {
      var failure = new SQLException(CLEAN);
      failure.addSuppressed(new IllegalStateException(PERSONAL_VALUE));

      assertThatThrownBy(
              () -> DatabaseFailureAssertions.assertChainCarriesNo(failure, PERSONAL_VALUE))
          .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("passes when no member carries the value")
    void passesOnCleanChain() {
      var failure = new IllegalStateException("could not save", new SQLException(CLEAN));

      assertThatNoException()
          .isThrownBy(
              () -> DatabaseFailureAssertions.assertChainCarriesNo(failure, PERSONAL_VALUE));
    }
  }

  @Nested
  @DisplayName("sqlExceptionIn")
  class SqlExceptionIn {

    @Test
    @DisplayName("returns the first SQLException in the chain")
    void returnsFirstSqlException() {
      var database = new SQLException(CLEAN);

      Throwable found =
          DatabaseFailureAssertions.sqlExceptionIn(new IllegalStateException("top", database));

      assertThat(found).isSameAs(database);
    }

    @Test
    @DisplayName("fails when the chain holds none")
    void failsWithoutSqlException() {
      var failure = new IllegalStateException("top");

      assertThatThrownBy(() -> DatabaseFailureAssertions.sqlExceptionIn(failure))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("no SQLException");
    }
  }

  @Nested
  @DisplayName("assertServiceSeesNo")
  @ExtendWith(OutputCaptureExtension.class)
  class ServiceSeesNo {

    @Test
    @DisplayName("fails when the logged failure carries the value")
    void failsWhenLogCarriesValue(CapturedOutput output) {
      var failure = failureWithValueInNextException();

      assertThatThrownBy(
              () -> DatabaseFailureAssertions.assertServiceSeesNo(failure, output, PERSONAL_VALUE))
          .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("passes when neither the log nor the chain carries the value")
    void passesOnCleanFailure(CapturedOutput output) {
      var failure = new IllegalStateException("could not save", new SQLException(CLEAN));

      assertThatNoException()
          .isThrownBy(
              () -> DatabaseFailureAssertions.assertServiceSeesNo(failure, output, PERSONAL_VALUE));
    }
  }
}
