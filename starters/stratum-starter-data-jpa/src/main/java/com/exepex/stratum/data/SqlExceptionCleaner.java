package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.sql.BatchUpdateException;
import java.sql.DataTruncation;
import java.sql.SQLClientInfoException;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLNonTransientException;
import java.sql.SQLRecoverableException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;
import java.sql.SQLWarning;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * Builds the clean copy of a database error: the same standard {@code java.sql} class, SQLState,
 * vendor code and stack trace, with no driver text except the first line of an error about the
 * system, and with its causes, next exceptions and suppressed exceptions cleaned the same way.
 */
@StratumInternal
public class SqlExceptionCleaner {

  /** The SQLState classes about the system, not the data, whose first line is kept. */
  private static final Set<String> SYSTEM_STATE_CLASSES =
      Set.of("08", "28", "40", "53", "57", "58");

  /** The connection classes, which describe the system whatever their SQLState. */
  private static final Set<Class<?>> CONNECTION_CLASSES =
      Set.of(SQLTransientConnectionException.class, SQLNonTransientConnectionException.class);

  /**
   * How each standard {@code java.sql} class with no fields of its own is copied, from its parts.
   */
  private static final Map<Class<?>, Function<Parts, SQLException>> COPY_BY_CLASS =
      Map.ofEntries(
          Map.entry(
              SQLException.class,
              parts ->
                  new SQLException(parts.message(), parts.state(), parts.code(), parts.cause())),
          Map.entry(
              SQLWarning.class,
              parts -> new SQLWarning(parts.message(), parts.state(), parts.code(), parts.cause())),
          Map.entry(
              SQLNonTransientException.class,
              parts ->
                  new SQLNonTransientException(
                      parts.message(), parts.state(), parts.code(), parts.cause())),
          Map.entry(
              SQLDataException.class,
              parts ->
                  new SQLDataException(
                      parts.message(), parts.state(), parts.code(), parts.cause())),
          Map.entry(
              SQLFeatureNotSupportedException.class,
              parts ->
                  new SQLFeatureNotSupportedException(
                      parts.message(), parts.state(), parts.code(), parts.cause())),
          Map.entry(
              SQLIntegrityConstraintViolationException.class,
              parts ->
                  new SQLIntegrityConstraintViolationException(
                      parts.message(), parts.state(), parts.code(), parts.cause())),
          Map.entry(
              SQLInvalidAuthorizationSpecException.class,
              parts ->
                  new SQLInvalidAuthorizationSpecException(
                      parts.message(), parts.state(), parts.code(), parts.cause())),
          Map.entry(
              SQLNonTransientConnectionException.class,
              parts ->
                  new SQLNonTransientConnectionException(
                      parts.message(), parts.state(), parts.code(), parts.cause())),
          Map.entry(
              SQLSyntaxErrorException.class,
              parts ->
                  new SQLSyntaxErrorException(
                      parts.message(), parts.state(), parts.code(), parts.cause())),
          Map.entry(
              SQLTransientException.class,
              parts ->
                  new SQLTransientException(
                      parts.message(), parts.state(), parts.code(), parts.cause())),
          Map.entry(
              SQLTimeoutException.class,
              parts ->
                  new SQLTimeoutException(
                      parts.message(), parts.state(), parts.code(), parts.cause())),
          Map.entry(
              SQLTransactionRollbackException.class,
              parts ->
                  new SQLTransactionRollbackException(
                      parts.message(), parts.state(), parts.code(), parts.cause())),
          Map.entry(
              SQLTransientConnectionException.class,
              parts ->
                  new SQLTransientConnectionException(
                      parts.message(), parts.state(), parts.code(), parts.cause())),
          Map.entry(
              SQLRecoverableException.class,
              parts ->
                  new SQLRecoverableException(
                      parts.message(), parts.state(), parts.code(), parts.cause())));

  /** The readers that write a value-free message naming the violated constraint. */
  private final List<Function<SQLException, Optional<String>>> constraintReaders;

  /**
   * Every copy this cleaner built, held weakly, so that a copy reaching a second boundary comes
   * back unchanged. {@code WeakHashMap} matches by {@code equals}, which {@code Throwable} leaves
   * as identity.
   */
  private final Set<Throwable> builtCopies =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

  /**
   * Creates the cleaner.
   *
   * @param constraintReaders the readers of each supported database, tried in order
   */
  public SqlExceptionCleaner(List<Function<SQLException, Optional<String>>> constraintReaders) {
    this.constraintReaders = List.copyOf(constraintReaders);
  }

  /**
   * Returns the clean copy of an error, or the error itself when it is already a clean copy.
   *
   * @param failure the driver's or pool's error, or a warning
   * @return its clean copy, of the same standard {@code java.sql} class
   */
  public SQLException cleaned(SQLException failure) {
    return SQLException.class.cast(cleaned(failure, new IdentityHashMap<>()));
  }

  /**
   * Returns the clean copy of any failure in a chain. {@code copied} maps each failure of this
   * chain already met to its copy, or to {@code null} while that copy is being built.
   */
  private @Nullable Throwable cleaned(Throwable failure, Map<Throwable, Throwable> copied) {
    if (builtCopies.contains(failure)) {
      return failure;
    }
    if (copied.containsKey(failure)) {
      // Met before: its copy, or null for a chain that loops back to a failure being copied.
      return copied.get(failure);
    }
    copied.put(failure, null);
    var copy = copyAlone(failure, copied);
    if (failure instanceof SQLException sqlFailure) {
      appendNextExceptions(SQLException.class.cast(copy), sqlFailure.getNextException(), copied);
    }
    copied.put(failure, copy);
    return copy;
  }

  /**
   * Builds the copy of one failure, with its cause and suppressed failures cleaned but without its
   * next exceptions. A failure that is no {@code SQLException} becomes an {@code Exception} that
   * names only its class.
   */
  private Throwable copyAlone(Throwable failure, Map<Throwable, Throwable> copied) {
    var cause = failure.getCause() == null ? null : cleaned(failure.getCause(), copied);
    Throwable copy =
        failure instanceof SQLException sqlFailure
            ? copyOf(sqlFailure, cause)
            : new Exception(failure.getClass().getName(), cause);
    copy.setStackTrace(failure.getStackTrace());
    Stream.of(failure.getSuppressed())
        .map(suppressed -> cleaned(suppressed, copied))
        .filter(Objects::nonNull)
        .forEach(copy::addSuppressed);
    builtCopies.add(copy);
    return copy;
  }

  /**
   * Appends the clean builtCopies of an error's next exceptions to its copy. A copy that already
   * exists, because the failure is clean or was met before in this chain, is attached once, as the
   * tail, with its own chain: attaching an element twice would make the chain loop.
   */
  private void appendNextExceptions(
      SQLException copy, @Nullable SQLException first, Map<Throwable, Throwable> copied) {
    // A loop, not a stream: the walk stops at the first copy that already exists, and each step
    // reads the original's chain while the copy's chain grows.
    var appended = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    // Each copy is attached to the last one, which has no next exception yet, because
    // setNextException walks the whole chain: attaching to the head would be quadratic.
    var last = copy;
    var next = first;
    while (next != null && !builtCopies.contains(next) && !copied.containsKey(next)) {
      copied.put(next, null);
      var nextCopy = SQLException.class.cast(copyAlone(next, copied));
      copied.put(next, nextCopy);
      last.setNextException(nextCopy);
      last = nextCopy;
      appended.add(nextCopy);
      next = next.getNextException();
    }
    Throwable tail = next == null || builtCopies.contains(next) ? next : copied.get(next);
    if (tail instanceof SQLException cleanTail && !appended.contains(cleanTail)) {
      last.setNextException(cleanTail);
    }
  }

  /** Builds the copy of one error, of its nearest standard {@code java.sql} class. */
  private SQLException copyOf(SQLException failure, @Nullable Throwable cause) {
    var standardClass =
        Stream.<Class<?>>iterate(failure.getClass(), Objects::nonNull, Class::getSuperclass)
            .filter(COPY_BY_CLASS::containsKey)
            .findFirst()
            .orElse(SQLException.class);
    var parts =
        Parts.builder()
            .original(failure)
            .message(messageOf(failure, standardClass))
            .cause(cause)
            .build();
    // The three classes with fields of their own are copied with those fields.
    return switch (failure) {
      case DataTruncation truncation -> parts.dataTruncation(truncation);
      case BatchUpdateException batchFailure -> parts.batchUpdateException(batchFailure);
      case SQLClientInfoException clientInfoFailure -> parts.clientInfoException(clientInfoFailure);
      default -> COPY_BY_CLASS.get(standardClass).apply(parts);
    };
  }

  /**
   * Returns the copy's message: the first line of an error about the system, a constraint reader's
   * message, or a fixed text naming the SQLState and the vendor code.
   */
  private String messageOf(SQLException failure, Class<?> standardClass) {
    var message = failure.getMessage();
    // A batch error's message is never kept: PostgreSQL's driver writes the statement with its
    // values into it and gives it the SQLState of the error beneath, which its next exception
    // carries and which is cleaned on its own.
    if (message != null
        && !(failure instanceof BatchUpdateException)
        && isAboutTheSystem(failure.getSQLState(), standardClass)) {
      return message.lines().findFirst().orElse("");
    }
    return constraintReaders.stream()
        .map(reader -> reader.apply(failure))
        .flatMap(Optional::stream)
        .findFirst()
        .orElseGet(() -> fixedText(failure));
  }

  /** Returns whether an error describes the system, not the data, by its class or SQLState. */
  private boolean isAboutTheSystem(@Nullable String state, Class<?> standardClass) {
    return CONNECTION_CLASSES.contains(standardClass)
        || state != null
            && state.length() >= 2
            && SYSTEM_STATE_CLASSES.contains(state.substring(0, 2));
  }

  /** Returns the fixed text of an error whose message is dropped. */
  private String fixedText(SQLException failure) {
    var state = Objects.requireNonNullElse(failure.getSQLState(), "unknown");
    return "SQLState " + state + ", vendor code " + failure.getErrorCode();
  }

  /**
   * What a copy is built from: the original's standard fields, the clean message and the clean
   * cause.
   *
   * @param original the driver's or pool's error
   * @param message the clean message
   * @param cause the clean copy of the original's cause, or {@code null}
   */
  @Builder
  private record Parts(SQLException original, String message, @Nullable Throwable cause) {

    /**
     * Returns the original's SQLState.
     *
     * @return the SQLState, or {@code null}
     */
    @Nullable String state() {
      return original.getSQLState();
    }

    /**
     * Returns the original's vendor code.
     *
     * @return the vendor code
     */
    int code() {
      return original.getErrorCode();
    }

    /**
     * Copies a {@code DataTruncation}, whose constructor sets its own message and SQLState.
     *
     * @param truncation the original
     * @return the copy
     */
    SQLException dataTruncation(DataTruncation truncation) {
      return new DataTruncation(
          truncation.getIndex(),
          truncation.getParameter(),
          truncation.getRead(),
          truncation.getDataSize(),
          truncation.getTransferSize(),
          cause);
    }

    /**
     * Copies a {@code BatchUpdateException} with its update counts.
     *
     * @param batchFailure the original
     * @return the copy
     */
    SQLException batchUpdateException(BatchUpdateException batchFailure) {
      return new BatchUpdateException(
          message, state(), code(), batchFailure.getLargeUpdateCounts(), cause);
    }

    /**
     * Copies an {@code SQLClientInfoException} with the names of the properties not set.
     *
     * @param clientInfoFailure the original
     * @return the copy
     */
    SQLException clientInfoException(SQLClientInfoException clientInfoFailure) {
      return new SQLClientInfoException(
          message, state(), code(), clientInfoFailure.getFailedProperties(), cause);
    }
  }
}
