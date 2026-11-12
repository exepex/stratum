package com.exepex.stratum.core.error;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * Finds the error code of a failure the way Stratum's error body and its metrics and spans do: the
 * failure's own code, else that of the first of its causes that has one.
 */
@StratumInternal
@UtilityClass
public class FailureCauses {

  /**
   * Returns the failure and its causes, in order, following a chain that loops back on itself once.
   *
   * @param failure the failure, if any
   * @return the failure and its causes; empty for none
   */
  public static Stream<Throwable> causesOf(@Nullable Throwable failure) {
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    return Stream.iterate(failure, cause -> cause != null && seen.add(cause), Throwable::getCause);
  }

  /**
   * Returns the failure itself or its first cause that implements {@link FailureWithErrorCode}.
   *
   * @param failure the failure, if any
   * @return the failure that carries the code; empty if none does
   */
  public static Optional<FailureWithErrorCode> findFailureWithErrorCode(
      @Nullable Throwable failure) {
    return causesOf(failure)
        .filter(FailureWithErrorCode.class::isInstance)
        .map(FailureWithErrorCode.class::cast)
        .findFirst();
  }
}
