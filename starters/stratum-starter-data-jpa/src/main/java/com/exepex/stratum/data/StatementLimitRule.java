package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;

/**
 * Decides how long one statement may run. Outside any deadline, the time limit the code set on the
 * statement wins, and otherwise the pool's {@code stratum.data.query-timeout}. Inside a deadline,
 * such as a transaction's, the statement ends by the earliest of the deadline and those limits.
 */
@StratumInternal
@UtilityClass
public class StatementLimitRule {

  /** Milliseconds in a second, for the limit the code sets in whole seconds. */
  private static final long MILLIS_PER_SECOND = 1000;

  /**
   * Returns the statement's limit, or none.
   *
   * @param callerSeconds the time limit the code set on the statement, in seconds; 0 for none
   * @param deadlines the time left before each deadline the statement runs under, such as its
   *     transaction's; may be 0 or less once a deadline has passed
   * @param poolMillis the pool's {@code query-timeout}, in milliseconds; 0 for none
   * @return the limit and what set it, or empty when nothing limits the statement
   */
  public Optional<Limit> limitOf(int callerSeconds, List<TimeLeft> deadlines, long poolMillis) {
    var limits = new ArrayList<Limit>();
    deadlines.forEach(deadline -> limits.add(new Limit(deadline.millis(), deadline.source())));
    if (callerSeconds > 0) {
      limits.add(new Limit(callerSeconds * MILLIS_PER_SECOND, LimitSource.CALLER));
    }
    // Outside a deadline the code's own limit replaces the pool's; inside one, both apply.
    if (poolMillis > 0 && (callerSeconds <= 0 || !deadlines.isEmpty())) {
      limits.add(new Limit(poolMillis, LimitSource.QUERY_TIMEOUT));
    }
    return limits.stream().min(Comparator.comparingLong(Limit::millis));
  }

  /** What set a statement's limit, as the timeout counter's {@code cause} tag names it. */
  @StratumInternal
  @Getter
  @RequiredArgsConstructor
  public enum LimitSource {

    /** The deadline of the transaction the statement runs in. */
    TRANSACTION("transaction"),

    /** The pool's {@code stratum.data.query-timeout}. */
    QUERY_TIMEOUT("query-timeout"),

    /** The time limit the code set on the statement, such as {@code setQueryTimeout}. */
    CALLER("caller");

    /** The name the counter's tag and the exception's message use. */
    private final String tag;
  }

  /**
   * The time left before one deadline a statement runs under.
   *
   * @param millis the time left, in milliseconds; 0 or less once it has passed
   * @param source what set the deadline
   */
  @StratumInternal
  public record TimeLeft(long millis, LimitSource source) {}

  /**
   * A statement's limit.
   *
   * @param millis how long the statement may run, in milliseconds; 0 or less means no time is left
   * @param source what set the limit
   */
  @StratumInternal
  public record Limit(long millis, LimitSource source) {}
}
