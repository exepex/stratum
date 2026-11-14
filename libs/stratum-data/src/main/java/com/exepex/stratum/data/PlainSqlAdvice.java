package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import lombok.experimental.UtilityClass;
import net.bytebuddy.asm.Advice;

/**
 * The code that the boundary's generated statement wrappers run before each {@code execute} that
 * takes its SQL: it gives the statement that SQL, which a span reads only for its first keyword. It
 * runs before {@link StatementLimitAdvice}, which starts the span. ByteBuddy copies it into each
 * method; ByteBuddy requires it to be static.
 */
@StratumInternal
@UtilityClass
public class PlainSqlAdvice {

  /**
   * Gives the statement the SQL of its execute.
   *
   * @param wrapper the statement wrapper
   * @param sql the SQL the execute runs
   */
  @Advice.OnMethodEnter
  public void keepSql(@Advice.This StatementWrapper wrapper, @Advice.Argument(0) String sql) {
    wrapper.executes(sql);
  }
}
