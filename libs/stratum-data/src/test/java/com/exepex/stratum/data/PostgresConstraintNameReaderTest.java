package com.exepex.stratum.data;

import static com.exepex.stratum.data.testsupport.SqlFailureTestData.COLUMN;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.FOREIGN_KEY_VIOLATION;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.INVALID_TEXT;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.UNIQUE_VIOLATION;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.postgresFailure;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.unnamedPostgresFailure;
import static com.exepex.stratum.test.DatabaseFailureAssertions.CONSTRAINT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.sql.SQLException;
import java.util.List;
import org.hibernate.dialect.PostgreSQLDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;

@DisplayName("PostgresConstraintNameReader names the violated constraint and nothing else")
class PostgresConstraintNameReaderTest {

  /** Each constraint SQLState with the message Hibernate's PostgreSQL dialect reads. */
  private static final List<Arguments> VIOLATIONS =
      List.of(
          arguments(
              UNIQUE_VIOLATION,
              "duplicate key value violates unique constraint \"" + CONSTRAINT + "\""),
          arguments(
              FOREIGN_KEY_VIOLATION, "violates foreign key constraint \"" + CONSTRAINT + "\""),
          arguments("23514", "violates check constraint \"" + CONSTRAINT + "\""),
          arguments(
              "23502", "null value in column \"" + COLUMN + "\" violates not-null constraint"));

  /** Errors the reader writes nothing for. */
  private static final List<Arguments> UNREAD_FAILURES =
      List.of(
          arguments("another SQLState", postgresFailure(INVALID_TEXT)),
          arguments(
              "a constraint violation that names nothing",
              unnamedPostgresFailure(UNIQUE_VIOLATION)),
          arguments(
              "a driver error without a server message",
              new PSQLException("x", PSQLState.UNIQUE_VIOLATION)),
          arguments(
              "a server message without a SQLState",
              new PSQLException(new ServerErrorMessage("Mx\0"))),
          arguments("another driver's error", new SQLException("x", UNIQUE_VIOLATION)));

  private final PostgresConstraintNameReader reader = new PostgresConstraintNameReader();

  @ParameterizedTest(name = "{0}")
  @FieldSource("VIOLATIONS")
  @DisplayName("a violation gets a message with the name only, as Hibernate's dialect reads it")
  void writesConstraintMessage(String state, String message) {
    assertThat(reader.apply(postgresFailure(state))).hasValue(message);
  }

  @ParameterizedTest(name = "{0}")
  @FieldSource("UNREAD_FAILURES")
  @DisplayName("any other error gets no message")
  void writesNothingForOtherErrors(String description, SQLException failure) {
    assertThat(reader.apply(failure)).isEmpty();
  }

  @Test
  @DisplayName("Hibernate's PostgreSQL dialect reads the constraint's name from the message")
  void feedsHibernateDialect() {
    var copy = new SqlExceptionCleaner(List.of(reader)).cleaned(postgresFailure(UNIQUE_VIOLATION));

    assertThat(
            new PostgreSQLDialect()
                .getViolatedConstraintNameExtractor()
                .extractConstraintName(copy))
        .isEqualTo(CONSTRAINT);
  }
}
