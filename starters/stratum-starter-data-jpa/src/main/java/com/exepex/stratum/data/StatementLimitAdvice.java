package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.sql.SQLException;
import lombok.experimental.UtilityClass;
import net.bytebuddy.asm.Advice;
import org.jspecify.annotations.Nullable;

/**
 * The code that the boundary's generated statement wrappers run around every {@code execute}
 * method: it starts the statement's time limit before the driver call and ends it after, around
 * {@link CleanErrorsAdvice}, so it sees the clean error. ByteBuddy copies it into each method, so
 * it costs no call of its own; ByteBuddy requires it to be static.
 */
@StratumInternal
@UtilityClass
public class StatementLimitAdvice {

  /**
   * Starts the statement's limit.
   *
   * @param wrapper the statement wrapper
   * @return the slot's value, which the exit receives
   * @throws SQLException {@code SX-DATA-1003} when no time is left; the driver is then not called
   */
  @Advice.OnMethodEnter
  public long startLimit(@Advice.This StatementWrapper wrapper) throws SQLException {
    return wrapper.enterLimit();
  }

  /**
   * Ends the statement's limit and returns what the call throws in its place.
   *
   * @param wrapper the statement wrapper
   * @param running the slot's value from the start
   * @param thrown what the call threw, already clean, or {@code null}
   * @return what the call throws, or {@code null} when it returns
   */
  @Advice.OnMethodExit(onThrowable = Throwable.class)
  @Advice.AssignReturned.ToThrown
  public @Nullable Throwable endLimit(
      @Advice.This StatementWrapper wrapper,
      @Advice.Enter long running,
      @Advice.Thrown @Nullable Throwable thrown) {
    return wrapper.exitLimit(running, thrown);
  }
}
