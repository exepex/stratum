package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Wrapper;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * The base of every JDBC object the boundary hands out: it holds the driver's or pool's object and
 * passes {@code unwrap} and {@code isWrapperFor} straight to it, so a service still reaches the
 * pool and the driver's own classes. Its subclasses are generated at startup, one per JDBC type.
 */
@StratumInternal
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class JdbcWrapper implements Wrapper {

  /** The pool's or driver's object that every call goes to. */
  protected final Object delegate;

  /** Cleans the errors and wraps the results of every call. */
  @Getter(AccessLevel.PACKAGE)
  protected final JdbcBoundary boundary;

  /** The wrapper that returned this one, such as the connection of a statement, or none. */
  protected final @Nullable JdbcWrapper parent;

  /**
   * Returns the pool's or driver's object of the given type, as the wrapped object finds it.
   *
   * @param type the type wanted, such as {@code HikariDataSource}
   * @param <T> the type wanted
   * @return the object found by the wrapped object
   * @throws SQLException the clean copy of the wrapped object's error
   */
  @Override
  public <T> T unwrap(Class<T> type) throws SQLException {
    try {
      return ((Wrapper) delegate).unwrap(type);
    } catch (SQLException failure) {
      throw boundary.cleaned(failure);
    }
  }

  /**
   * Returns whether the wrapped object reaches an object of the given type.
   *
   * @param type the type asked for
   * @return the wrapped object's answer
   * @throws SQLException the clean copy of the wrapped object's error
   */
  @Override
  public boolean isWrapperFor(Class<?> type) throws SQLException {
    try {
      return ((Wrapper) delegate).isWrapperFor(type);
    } catch (SQLException failure) {
      throw boundary.cleaned(failure);
    }
  }

  /**
   * Returns the wrapper of an object a call returned: the object itself when it is already a
   * wrapper, this wrapper or its parent when the object is theirs, or a new wrapper.
   *
   * @param returned the object the call returned
   * @param type the JDBC type the call declares
   * @return the wrapper, or {@code null} for a {@code null} result
   */
  @Nullable Object wrapped(@Nullable Object returned, Class<?> type) {
    if (returned == null || returned instanceof JdbcWrapper) {
      return returned;
    }
    // The pool's and driver's JDBC objects keep Object.equals, so this finds the same object.
    if (delegate.equals(returned)) {
      return this;
    }
    if (parent != null && parent.delegate.equals(returned)) {
      return parent;
    }
    return boundary.wrapResult(returned, type, this);
  }

  /**
   * Returns the wrapper of an object a call returned as {@code Object}, such as a {@code Blob} from
   * {@code getObject}, or the object itself when it is no wrapped JDBC type.
   *
   * @param returned the object the call returned
   * @return the wrapper, the object itself, or {@code null}
   */
  @Nullable Object wrappedObject(@Nullable Object returned) {
    var type = returned == null ? null : boundary.wrappedTypeOf(returned);
    return type == null ? returned : wrapped(returned, type);
  }

  /**
   * Returns the clean copy of the warnings a call returned.
   *
   * @param warnings the driver's warnings, or {@code null}
   * @return their clean copy, or {@code null}
   */
  @Nullable SQLWarning cleanedWarnings(@Nullable SQLWarning warnings) {
    return warnings == null ? null : SQLWarning.class.cast(boundary.cleaned(warnings));
  }

  /**
   * Returns the driver's own object for an argument that is a wrapper, so that the driver gets back
   * the savepoint, array or large object it created.
   *
   * @param argument the argument the service passed
   * @return the wrapped object, or the argument itself
   */
  @Nullable Object unwrappedArgument(@Nullable Object argument) {
    return argument instanceof JdbcWrapper wrapper ? wrapper.delegate : argument;
  }

  /**
   * Returns the clean copy of what a call threw, for the generated code to rethrow.
   *
   * @param failure what the call threw
   * @return the clean copy of an {@code SQLException}, or the failure itself
   */
  Throwable cleanedFailure(Throwable failure) {
    return failure instanceof SQLException sqlFailure ? boundary.cleaned(sqlFailure) : failure;
  }
}
