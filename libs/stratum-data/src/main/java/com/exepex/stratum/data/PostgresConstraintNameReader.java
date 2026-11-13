package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Function;
import org.postgresql.util.PSQLException;

/**
 * Writes the message of a PostgreSQL constraint violation with only the constraint's or column's
 * name, in the words Hibernate's PostgreSQL dialect reads, so {@code getConstraintName()} keeps
 * working while the values in the driver's message are dropped.
 */
@StratumInternal
public class PostgresConstraintNameReader implements Function<SQLException, Optional<String>> {

  /** The closing quote around a name, which Hibernate's dialect reads up to. */
  private static final String QUOTE = "\"";

  /**
   * Returns the value-free message of a unique, foreign key, check or not-null violation.
   *
   * @param failure the driver's error
   * @return the message, or empty for any other error or when the server named nothing
   */
  @Override
  public Optional<String> apply(SQLException failure) {
    if (!(failure instanceof PSQLException postgresFailure)
        || postgresFailure.getServerErrorMessage() == null
        || failure.getSQLState() == null) {
      return Optional.empty();
    }
    var server = postgresFailure.getServerErrorMessage();
    return switch (failure.getSQLState()) {
      case "23505" ->
          named("duplicate key value violates unique constraint ", server.getConstraint(), "");
      case "23503" -> named("violates foreign key constraint ", server.getConstraint(), "");
      case "23514" -> named("violates check constraint ", server.getConstraint(), "");
      case "23502" ->
          named("null value in column ", server.getColumn(), " violates not-null constraint");
      default -> Optional.empty();
    };
  }

  /** Returns the message with the quoted name between its two parts, or empty without a name. */
  private Optional<String> named(String before, String name, String after) {
    return Optional.ofNullable(name).map(present -> before + QUOTE + present + QUOTE + after);
  }
}
