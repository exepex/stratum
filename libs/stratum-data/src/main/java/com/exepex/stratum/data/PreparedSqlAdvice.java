package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import lombok.experimental.UtilityClass;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * The code that the boundary's generated connection wrappers run after each {@code
 * prepareStatement} and {@code prepareCall}: it gives the statement its SQL, which a span reads
 * only for its first keyword. ByteBuddy copies it into each method; ByteBuddy requires it to be
 * static.
 */
@StratumInternal
@UtilityClass
public class PreparedSqlAdvice {

  /**
   * Gives the prepared statement its SQL.
   *
   * @param sql the statement's SQL
   * @param returned the statement wrapper the call returned
   */
  @Advice.OnMethodExit
  public void keepSql(
      @Advice.Argument(0) String sql,
      @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returned) {
    if (returned instanceof StatementWrapper statement) {
      statement.prepared(sql);
    }
  }
}
