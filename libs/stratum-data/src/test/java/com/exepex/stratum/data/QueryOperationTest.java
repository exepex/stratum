package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.exepex.stratum.test.CpuCostMeter;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("QueryOperation reads only the first keyword after white space and comments")
class QueryOperationTest {

  /** A megabyte of block comment with no end, the longest scan a statement can force. */
  private static final String HOSTILE_COMMENT = "/*" + "x".repeat(1_000_000);

  /**
   * The most CPU time one scan of the hostile comment may take: about 10 times the 117 µs measured
   * on the build machine, so a 10× slowdown fails.
   */
  private static final Duration HOSTILE_LIMIT = Duration.ofNanos(1_200_000);

  /**
   * Half a million nested block comments with no end, the deepest nesting a statement can force.
   */
  private static final String HOSTILE_NESTING = "/*".repeat(500_000);

  /**
   * The most CPU time one scan of the hostile nesting may take: about 10 times the 4.5 ms measured
   * on the build machine, where each {@code *} costs one search, so a 10× slowdown fails; a scan
   * that rescans the text for each opening would take seconds.
   */
  private static final Duration HOSTILE_NESTING_LIMIT = Duration.ofMillis(45);

  /** Scans in each timed batch: a few milliseconds of work. */
  private static final int SCANS_PER_BATCH = 5;

  /** Each SQL text with the operation it starts with. */
  private static final List<Arguments> OPERATIONS =
      List.of(
          arguments("SELECT 1", QueryOperation.SELECT),
          arguments("select 1", QueryOperation.SELECT),
          arguments("  \n\tInsert into t values (1)", QueryOperation.INSERT),
          arguments("update t set a = 1", QueryOperation.UPDATE),
          arguments("DELETE FROM t", QueryOperation.DELETE),
          arguments("-- a comment\nMERGE INTO t", QueryOperation.MERGE),
          arguments("/* a */ /* b */ WITH x AS (SELECT 1) SELECT 1", QueryOperation.WITH),
          arguments("call p()", QueryOperation.CALL),
          arguments("COMMIT", QueryOperation.COMMIT),
          arguments("SELECT(1)", QueryOperation.SELECT),
          arguments("", QueryOperation.UNKNOWN),
          arguments("   ", QueryOperation.UNKNOWN),
          arguments("-- only a comment", QueryOperation.UNKNOWN),
          arguments("/* unterminated SELECT", QueryOperation.UNKNOWN),
          arguments("/* outer /* inner */ DROP TABLE t */ SELECT 1", QueryOperation.SELECT),
          arguments("/* a /* b */ c /* d */ e */ UPDATE t", QueryOperation.UPDATE),
          arguments("/* outer /* inner */ SELECT 1", QueryOperation.UNKNOWN),
          arguments("{call p()}", QueryOperation.UNKNOWN),
          arguments("EXEC p", QueryOperation.UNKNOWN),
          arguments("SELECTED 1", QueryOperation.UNKNOWN),
          arguments("TRUNCATED", QueryOperation.UNKNOWN),
          arguments("1 SELECT", QueryOperation.UNKNOWN),
          arguments("unknown", QueryOperation.UNKNOWN),
          arguments("batch", QueryOperation.UNKNOWN),
          arguments("ada@example.com", QueryOperation.UNKNOWN));

  @ParameterizedTest(name = "{1} for \"{0}\"")
  @FieldSource("OPERATIONS")
  @DisplayName("gives the keyword in upper case, or UNKNOWN for anything else")
  void readsFirstKeyword(String sql, QueryOperation operation) {
    assertThat(QueryOperation.of(sql)).isEqualTo(operation);
  }

  /** An insert, the first SQL of most batches. */
  private static final String INSERT = "INSERT INTO t VALUES (1)";

  /** Each plain batch with its operation. */
  private static final List<Arguments> BATCHES =
      List.of(
          arguments(List.of(INSERT), QueryOperation.INSERT),
          arguments(List.of(INSERT, "insert into t values (2)"), QueryOperation.INSERT),
          arguments(List.of("UPDATE t SET a = 1", "UPDATE t SET a = 1"), QueryOperation.UPDATE),
          arguments(List.of(INSERT, "DELETE FROM t"), QueryOperation.BATCH),
          arguments(List.of("DELETE FROM t", "DELETE FROM u", INSERT), QueryOperation.BATCH),
          arguments(List.of("EXEC p", "EXEC q"), QueryOperation.UNKNOWN));

  @ParameterizedTest(name = "{1} for {0}")
  @FieldSource("BATCHES")
  @DisplayName("gives a batch its first operation, or BATCH when another differs")
  void readsBatch(List<String> batch, QueryOperation operation) {
    assertThat(QueryOperation.ofBatch(batch)).isEqualTo(operation);
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

  @Test
  @DisplayName("scans half a million nested comment openings in linear time")
  void linearOnHostileNesting() {
    new CpuCostMeter(SCANS_PER_BATCH)
        .assertCpuCostWithin(
            "half a million nested comment openings",
            HOSTILE_NESTING_LIMIT,
            () -> assertThat(QueryOperation.of(HOSTILE_NESTING)).isEqualTo(QueryOperation.UNKNOWN));
  }
}
