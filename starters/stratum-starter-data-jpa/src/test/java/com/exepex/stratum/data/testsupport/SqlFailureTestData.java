package com.exepex.stratum.data.testsupport;

import com.exepex.stratum.data.PostgresConstraintNameReader;
import com.exepex.stratum.data.SqlExceptionCleaner;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import lombok.experimental.UtilityClass;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/** Database errors that carry a personal value, as PostgreSQL's driver reports them. */
@UtilityClass
public class SqlFailureTestData {

  /** A personal value that a driver's message carries and no clean copy may show. */
  public static final String PERSONAL_VALUE = "ada.lovelace@example.com";

  /** The name of the constraint the errors violate. */
  public static final String CONSTRAINT = "customers_email_key";

  /** The name of the column the not-null errors name. */
  public static final String COLUMN = "email";

  /** The SQLState of a text that is no valid value of its type. */
  public static final String INVALID_TEXT = "22P02";

  /** The SQLState of a unique constraint violated. */
  public static final String UNIQUE_VIOLATION = "23505";

  /** The SQLState of a foreign key violated. */
  public static final String FOREIGN_KEY_VIOLATION = "23503";

  /** The SQLState of a connection lost. */
  public static final String CONNECTION_FAILURE = "08006";

  /**
   * Returns PostgreSQL's error for a SQLState, naming the constraint and the column, with the
   * personal value in its message and in its detail line.
   *
   * @param state the SQLState, such as {@code 23505}
   * @return the driver's error
   */
  public static PSQLException postgresFailure(String state) {
    return postgresFailure(state, "the write");
  }

  /**
   * Returns PostgreSQL's error for a SQLState and the operation that failed, naming the constraint
   * and the column, with the personal value in its message and in its detail line.
   *
   * @param state the SQLState, such as {@code 23505}
   * @param operation what failed, which the message names
   * @return the driver's error
   */
  public static PSQLException postgresFailure(String state, String operation) {
    return new PSQLException(
        new ServerErrorMessage(
            field('S', "ERROR")
                + field('C', state)
                + field('M', operation + " of " + PERSONAL_VALUE + " is not allowed")
                + field('D', "Key (email)=(" + PERSONAL_VALUE + ") already exists.")
                + field('n', CONSTRAINT)
                + field('c', COLUMN)));
  }

  /**
   * Returns PostgreSQL's error for a SQLState that names neither a constraint nor a column.
   *
   * @param state the SQLState
   * @return the driver's error
   */
  public static PSQLException unnamedPostgresFailure(String state) {
    return new PSQLException(
        new ServerErrorMessage(
            field('S', "ERROR") + field('C', state) + field('M', "bad " + PERSONAL_VALUE)));
  }

  /**
   * Returns a cleaner with the PostgreSQL reader, as the boundary builds it with the driver
   * present.
   *
   * @return the cleaner
   */
  public static SqlExceptionCleaner postgresCleaner() {
    return new SqlExceptionCleaner(postgresReaders());
  }

  /**
   * Returns the constraint readers the boundary uses when the PostgreSQL driver is present.
   *
   * @return the PostgreSQL reader alone
   */
  public static List<Function<SQLException, Optional<String>>> postgresReaders() {
    return List.of(new PostgresConstraintNameReader());
  }

  /** Returns one field of a server error, in the protocol's form: its code, its text, a zero. */
  private static String field(char code, String text) {
    return code + text + '\0';
  }
}
