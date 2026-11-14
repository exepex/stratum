package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.exepex.stratum.test.CpuCostMeter;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("QueryOperation reads only the first keyword after white space and comments")
class QueryOperationTest {

  /** A megabyte of block comment with no end, the longest scan a statement can force. */
  private static final String HOSTILE_COMMENT = "/*" + "x".repeat(1_000_000);

  /**
   * The most CPU time one scan of the hostile comment may take: about 10 times the 117 µs measured
   * on the build machine, so a 10× slowdown fails.
   */
  private static final Duration HOSTILE_LIMIT = Duration.ofNanos(1_200_000);

  /** Scans in each timed batch: a few milliseconds of work. */
  private static final int SCANS_PER_BATCH = 5;

  /**
   * Returns each SQL text with the operation it starts with.
   *
   * @return the SQL and its operation
   */
  static Stream<Arguments> operations() {
    return Stream.of(
        arguments("SELECT 1", "SELECT"),
        arguments("select 1", "SELECT"),
        arguments("  \n\tInsert into t values (1)", "INSERT"),
        arguments("update t set a = 1", "UPDATE"),
        arguments("DELETE FROM t", "DELETE"),
        arguments("-- a comment\nMERGE INTO t", "MERGE"),
        arguments("/* a */ /* b */ WITH x AS (SELECT 1) SELECT 1", "WITH"),
        arguments("call p()", "CALL"),
        arguments("COMMIT", "COMMIT"),
        arguments("SELECT(1)", "SELECT"),
        arguments("", QueryOperation.UNKNOWN),
        arguments("   ", QueryOperation.UNKNOWN),
        arguments("-- only a comment", QueryOperation.UNKNOWN),
        arguments("/* unterminated SELECT", QueryOperation.UNKNOWN),
        arguments("{call p()}", QueryOperation.UNKNOWN),
        arguments("EXEC p", QueryOperation.UNKNOWN),
        arguments("SELECTED 1", QueryOperation.UNKNOWN),
        arguments("TRUNCATED", QueryOperation.UNKNOWN),
        arguments("1 SELECT", QueryOperation.UNKNOWN),
        arguments("ada@example.com", QueryOperation.UNKNOWN));
  }

  @ParameterizedTest(name = "{1} for \"{0}\"")
  @MethodSource("operations")
  @DisplayName("gives the keyword in upper case, or UNKNOWN for anything else")
  void readsFirstKeyword(String sql, String operation) {
    assertThat(QueryOperation.of(sql)).isEqualTo(operation);
  }

  @Test
  @DisplayName("scans a megabyte of unterminated comment in linear time")
  void linearOnHostileComment() {
    new CpuCostMeter(SCANS_PER_BATCH)
        .assertCpuCostWithin(
            "a megabyte of unterminated comment",
            HOSTILE_LIMIT,
            () -> assertThat(QueryOperation.of(HOSTILE_COMMENT)).isEqualTo(QueryOperation.UNKNOWN));
  }
}
