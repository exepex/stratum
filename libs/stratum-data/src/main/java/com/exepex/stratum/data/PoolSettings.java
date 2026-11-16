package com.exepex.stratum.data;

import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The settings of one named pool, bound to {@code stratum.data.sources.<name>.*}. A named pool
 * takes none of the main pool's {@code spring.datasource.*} settings. The descriptions of the
 * components are what IDEs show, so they are plain sentences.
 *
 * @param url The pool's JDBC URL. Not needed when the service supplies a JdbcConnectionDetails bean
 *     named after the pool, such as batchJdbcConnectionDetails.
 * @param username The user name the pool logs in with.
 * @param password The password the pool logs in with. Set it from a secret, such as
 *     ${BATCH_DB_PASSWORD}; Stratum never logs it.
 * @param hikari HikariCP's settings for the pool, spelled as under spring.datasource.hikari, such
 *     as maximum-pool-size. A named pool keeps no idle connection and opens at most 10 unless they
 *     say otherwise.
 * @param queryTimeout How long one SQL statement on this pool may run before Stratum cancels it,
 *     when the code sets no limit. Defaults to stratum.data.query-timeout.
 * @param transactionTimeout The time limit of a transaction on this pool's transaction manager that
 *     sets none of its own. Defaults to spring.transaction.default-timeout.
 * @param fetchSize How many rows the driver fetches at a time for each statement on this pool,
 *     until the code sets its own. Unset means the driver's default.
 * @param packages The packages whose JPA repositories run on this pool, such as com.acme.reporting,
 *     each with its subpackages. A repository's @UseDataSource wins. Unset means none.
 */
@Builder(toBuilder = true)
public record PoolSettings(
    @Nullable String url,
    @Nullable String username,
    @Nullable String password,
    Map<String, String> hikari,
    List<String> packages,
    @Nullable Duration queryTimeout,
    @Nullable Duration transactionTimeout,
    @Nullable Integer fetchSize) {

  /**
   * Returns whether the time limits and the fetch size, when set, are 0 or more. Validation reports
   * a negative one as {@code stratum.data.sources[<name>].not-negative breaks @AssertTrue}.
   *
   * @return whether none is negative
   */
  @AssertTrue
  public boolean isNotNegative() {
    return (queryTimeout == null || !queryTimeout.isNegative())
        && (transactionTimeout == null || !transactionTimeout.isNegative())
        && (fetchSize == null || fetchSize >= 0);
  }

  /** Keeps HikariCP's settings and the packages fixed, empty when none is set. */
  public PoolSettings {
    hikari = hikari == null ? Map.of() : Map.copyOf(hikari);
    packages = packages == null ? List.of() : List.copyOf(packages);
  }

  /**
   * Shows which settings are set, never the user name or password.
   *
   * @return the settings without credentials
   */
  @Override
  public String toString() {
    return ("PoolSettings[url=%s, hikari=%s, packages=%s, queryTimeout=%s, transactionTimeout=%s,"
            + " fetchSize=%s]")
        .formatted(
            url == null ? "unset" : "set",
            hikari.keySet(),
            packages,
            queryTimeout,
            transactionTimeout,
            fetchSize);
  }
}
