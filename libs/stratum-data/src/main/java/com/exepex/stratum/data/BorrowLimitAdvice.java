package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.sql.SQLException;
import lombok.experimental.UtilityClass;
import net.bytebuddy.asm.Advice;

/**
 * The code that the boundary's generated data source wrapper runs before every {@code
 * getConnection}: it fails the borrow at once when the web request it serves has no time left.
 * ByteBuddy copies it into each method, so it costs no call of its own; ByteBuddy requires it to be
 * static.
 */
@StratumInternal
@UtilityClass
public class BorrowLimitAdvice {

  /**
   * Checks the time left before the pool is asked.
   *
   * @param wrapper the data source wrapper
   * @throws SQLException {@code SX-DATA-1003} when no time is left; the pool is then not asked
   */
  @Advice.OnMethodEnter
  public void checkBorrow(@Advice.This DataSourceWrapper wrapper) throws SQLException {
    wrapper.enterBorrow();
  }
}
