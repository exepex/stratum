package com.exepex.stratum.web.testsupport;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;

/** Database failures as Spring, Hibernate and the PostgreSQL driver raise them, for the tests. */
@UtilityClass
public class DatabaseFailureTestData {

  /**
   * What the PostgreSQL driver writes for a duplicate key: the SQL, the constraint and the value,
   * none of which may reach a caller.
   */
  public static final String DUPLICATE_KEY_MESSAGE =
      "ERROR: duplicate key value violates unique constraint \"orders_email_key\" Detail: Key"
          + " (email)=(ada@example.com) already exists. insert into orders (email) values (?)";

  /** The parts of {@link #DUPLICATE_KEY_MESSAGE} that may never reach a caller. */
  public static final List<String> PRIVATE_PARTS =
      List.of("orders_email_key", "ada@example.com", "insert into", "orders");

  /**
   * Returns a database error with a SQLState and the driver's message.
   *
   * @param sqlState the SQLState, or {@code null} for an error without one
   * @return the error
   */
  public static SQLException sqlFailure(@Nullable String sqlState) {
    return new SQLException(DUPLICATE_KEY_MESSAGE, sqlState);
  }

  /**
   * Returns a duplicate key as Spring reports it after Hibernate, whose message repeats the SQL and
   * the constraint.
   *
   * @return the failure
   */
  public static DataIntegrityViolationException duplicateKey() {
    return new DataIntegrityViolationException(
        "could not execute statement ["
            + DUPLICATE_KEY_MESSAGE
            + "]; constraint [orders_email_key]",
        sqlFailure("23505"));
  }

  /**
   * Returns a failure whose causes go {@code links} deep before they reach the given root.
   *
   * @param links how many wrappers sit above the root
   * @param root the deepest cause
   * @return the outermost failure
   */
  public static Throwable wrapped(int links, Throwable root) {
    return Stream.iterate(root, cause -> new IllegalStateException("wrapper", cause))
        .skip(links)
        .findFirst()
        .orElseThrow();
  }
}
