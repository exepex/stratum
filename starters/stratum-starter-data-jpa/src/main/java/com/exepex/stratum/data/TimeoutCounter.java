package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.data.StatementLimitRule.LimitSource;

/** Counts the statements Stratum cancelled, by data source bean and by what set the limit. */
@StratumInternal
@FunctionalInterface
public interface TimeoutCounter {

  /** Counts nothing, for a service without Micrometer. */
  TimeoutCounter NONE =
      new TimeoutCounter() {
        @Override
        public void count(String pool, LimitSource source) {
          // Without Micrometer there is no counter to add to.
        }
      };

  /**
   * Counts one cancelled statement.
   *
   * @param pool the data source bean's name
   * @param source what set the limit
   */
  void count(String pool, LimitSource source);
}
