package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import lombok.experimental.UtilityClass;
import net.bytebuddy.asm.Advice;

/**
 * The code that the boundary's generated statement wrappers run before each {@code
 * addBatch(String)}: it gives the statement the batch's SQL, which a span reads only for its first
 * keyword. ByteBuddy copies it into each method; ByteBuddy requires it to be static.
 */
@StratumInternal
@UtilityClass
public class BatchSqlAdvice {

  /**
   * Gives the statement the SQL added to its batch.
   *
   * @param wrapper the statement wrapper
   * @param sql the SQL added to the batch
   */
  @Advice.OnMethodEnter
  public void keepSql(@Advice.This StatementWrapper wrapper, @Advice.Argument(0) String sql) {
    wrapper.batches(sql);
  }
}
