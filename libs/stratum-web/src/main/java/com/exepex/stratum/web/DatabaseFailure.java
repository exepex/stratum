package com.exepex.stratum.web;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.FailureCauses;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * The kinds of database failure that a service answers with their own status instead of 500, in
 * strict mode: a database that is busy or too slow, and a request that conflicts with the stored
 * data. A failure is recognised by the JDK's {@code java.sql} types, the SQLState, and the names of
 * Spring's and Hibernate's exception classes, so the web module needs neither library.
 */
@StratumInternal
@RequiredArgsConstructor
public enum DatabaseFailure {

  /**
   * A statement timeout, a wait for a pooled connection, a lock timeout, a deadlock or a
   * transaction that ran out of time.
   */
  BUSY(
      WebErrorCode.DATABASE_BUSY,
      HttpStatus.SERVICE_UNAVAILABLE,
      Set.of(SQLTimeoutException.class, SQLTransientConnectionException.class),
      Set.of(WebConstants.TRANSACTION_TIMED_OUT),
      Set.of(
          WebConstants.STATEMENT_CANCELLED,
          WebConstants.LOCK_NOT_AVAILABLE,
          WebConstants.DEADLOCK)),

  /** A duplicate key, or a row that another request changed since it was read. */
  CONFLICT(
      WebErrorCode.DATA_CONFLICT,
      HttpStatus.CONFLICT,
      Set.of(),
      Set.of(WebConstants.OPTIMISTIC_LOCK_FAILURE, WebConstants.STALE_STATE),
      Set.of(WebConstants.UNIQUE_VIOLATION));

  /** The code the caller gets. */
  @Getter private final WebErrorCode code;

  /** The status the caller gets unless {@code stratum.web.error.statuses} maps the code. */
  @Getter private final HttpStatus defaultStatus;

  /** The JDK exception types of this kind, matched with their subclasses. */
  private final Set<Class<? extends SQLException>> sqlTypes;

  /** The names of Spring's and Hibernate's exception classes of this kind, with subclasses. */
  private final Set<String> classNames;

  /** The SQLStates of this kind. */
  private final Set<String> sqlStates;

  /**
   * Returns the kind of the first of the failure and its causes that is a known database failure. A
   * chain of causes that loops back on itself is followed once.
   *
   * @param failure what the request failed with
   * @return its kind; empty for any other failure
   */
  public static Optional<DatabaseFailure> of(@Nullable Throwable failure) {
    return FailureCauses.causesOf(failure).flatMap(DatabaseFailure::kindsOf).findFirst();
  }

  /** Returns the kinds one failure, without its causes, belongs to, the first kind first. */
  private static Stream<DatabaseFailure> kindsOf(Throwable failure) {
    return Stream.of(values()).filter(kind -> kind.matches(failure));
  }

  /** Returns whether one failure, without its causes, is of this kind. */
  private boolean matches(Throwable failure) {
    return sqlTypes.stream().anyMatch(type -> type.isInstance(failure))
        || classAndSuperclassNames(failure).anyMatch(classNames::contains)
        || failure instanceof SQLException sqlFailure && hasStateOfThisKind(sqlFailure);
  }

  /** Returns the names of the failure's class and of each of its superclasses up to Object. */
  private Stream<String> classAndSuperclassNames(Throwable failure) {
    return Stream.<Class<?>>iterate(failure.getClass(), Objects::nonNull, Class::getSuperclass)
        .map(Class::getName);
  }

  /** Returns whether a database error's SQLState, which may be missing, is of this kind. */
  private boolean hasStateOfThisKind(SQLException failure) {
    return Optional.ofNullable(failure.getSQLState()).filter(sqlStates::contains).isPresent();
  }
}
