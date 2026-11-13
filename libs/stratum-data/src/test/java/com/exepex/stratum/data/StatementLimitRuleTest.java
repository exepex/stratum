package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.StatementLimitRule.Limit;
import com.exepex.stratum.data.StatementLimitRule.LimitSource;
import com.exepex.stratum.data.StatementLimitRule.TimeLeft;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("StatementLimitRule decides how long a statement may run")
class StatementLimitRuleTest {

  /** The pool's query-timeout of these tests: 30 seconds. */
  private static final long POOL_MILLIS = 30_000;

  /** Milliseconds in a second. */
  private static final long MILLIS_PER_SECOND = 1_000;

  /** A limit the code sets that is longer than the pool's, in seconds: 10 minutes. */
  private static final int LONG_CALLER_SECONDS = 600;

  /** That limit in milliseconds. */
  private static final long LONG_CALLER_MILLIS = LONG_CALLER_SECONDS * MILLIS_PER_SECOND;

  /** A limit the code sets that is shorter than everything else, in seconds. */
  private static final int SHORT_CALLER_SECONDS = 2;

  /** A transaction with 5 seconds left. */
  private static final List<TimeLeft> FIVE_SECONDS_LEFT =
      List.of(new TimeLeft(5_000, LimitSource.TRANSACTION));

  /** A transaction with a minute left, longer than the pool's limit. */
  private static final List<TimeLeft> ONE_MINUTE_LEFT =
      List.of(new TimeLeft(60_000, LimitSource.TRANSACTION));

  /** A transaction whose deadline passed 10 milliseconds ago. */
  private static final List<TimeLeft> PASSED_DEADLINE =
      List.of(new TimeLeft(-10, LimitSource.TRANSACTION));

  @Nested
  @DisplayName("outside any deadline")
  class OutsideDeadline {

    @Test
    @DisplayName("the code's own limit replaces the pool's, even when it is longer")
    void callerWins() {
      assertThat(StatementLimitRule.limitOf(LONG_CALLER_SECONDS, List.of(), POOL_MILLIS))
          .contains(new Limit(LONG_CALLER_MILLIS, LimitSource.CALLER));
    }

    @Test
    @DisplayName("the pool's limit applies when the code sets none")
    void poolApplies() {
      assertThat(StatementLimitRule.limitOf(0, List.of(), POOL_MILLIS))
          .contains(new Limit(POOL_MILLIS, LimitSource.QUERY_TIMEOUT));
    }

    @Test
    @DisplayName("nothing limits the statement when neither sets a limit")
    void noLimit() {
      assertThat(StatementLimitRule.limitOf(0, List.of(), 0)).isEmpty();
    }

    @Test
    @DisplayName("the largest limit the code can set fits, in milliseconds")
    void largestCallerLimit() {
      assertThat(StatementLimitRule.limitOf(Integer.MAX_VALUE, List.of(), POOL_MILLIS))
          .contains(new Limit(Integer.MAX_VALUE * MILLIS_PER_SECOND, LimitSource.CALLER));
    }
  }

  @Nested
  @DisplayName("inside a transaction's deadline")
  class InsideDeadline {

    @Test
    @DisplayName("the deadline wins when it ends first")
    void deadlineWins() {
      assertThat(StatementLimitRule.limitOf(LONG_CALLER_SECONDS, FIVE_SECONDS_LEFT, POOL_MILLIS))
          .contains(new Limit(FIVE_SECONDS_LEFT.getFirst().millis(), LimitSource.TRANSACTION));
    }

    @Test
    @DisplayName("the pool's limit still applies when it ends first, even with the code's own")
    void poolStillApplies() {
      assertThat(StatementLimitRule.limitOf(LONG_CALLER_SECONDS, ONE_MINUTE_LEFT, POOL_MILLIS))
          .contains(new Limit(POOL_MILLIS, LimitSource.QUERY_TIMEOUT));
    }

    @Test
    @DisplayName("the code's own limit wins when it ends first")
    void callerWinsWhenShorter() {
      assertThat(StatementLimitRule.limitOf(SHORT_CALLER_SECONDS, FIVE_SECONDS_LEFT, POOL_MILLIS))
          .contains(new Limit(SHORT_CALLER_SECONDS * MILLIS_PER_SECOND, LimitSource.CALLER));
    }

    @Test
    @DisplayName("a deadline that has passed leaves no time, which fails the statement at once")
    void passedDeadline() {
      assertThat(StatementLimitRule.limitOf(0, PASSED_DEADLINE, POOL_MILLIS))
          .contains(new Limit(PASSED_DEADLINE.getFirst().millis(), LimitSource.TRANSACTION));
    }
  }
}
