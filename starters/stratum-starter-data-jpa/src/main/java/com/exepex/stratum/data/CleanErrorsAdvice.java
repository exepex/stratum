package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.sql.SQLException;
import lombok.experimental.UtilityClass;
import net.bytebuddy.asm.Advice;
import org.jspecify.annotations.Nullable;

/**
 * The code that the boundary's generated wrappers run after every JDBC call: it replaces an {@code
 * SQLException} the call threw with its clean copy. ByteBuddy copies it into each wrapper method,
 * so it costs no call of its own; ByteBuddy requires it to be static.
 */
@StratumInternal
@UtilityClass
public class CleanErrorsAdvice {

  /**
   * Returns the clean copy of what the call threw, which ByteBuddy throws in its place.
   *
   * @param wrapper the wrapper whose call threw
   * @param failure what the call threw, or {@code null} when it returned normally
   * @return the clean copy, or {@code null} when nothing was thrown
   */
  @Advice.OnMethodExit(onThrowable = SQLException.class)
  @Advice.AssignReturned.ToThrown
  public @Nullable Throwable cleanFailure(
      @Advice.This JdbcWrapper wrapper, @Advice.Thrown @Nullable Throwable failure) {
    return failure == null ? null : wrapper.cleanedFailure(failure);
  }
}
