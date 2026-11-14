package com.exepex.stratum.data.testsupport;

import com.exepex.stratum.data.DataProperties;
import com.exepex.stratum.data.DataPropertiesCustomizer;
import com.exepex.stratum.data.PoolSettings;
import java.time.Duration;
import java.util.Map;

/**
 * Adds the pool {@code reports} in Java only, with no key, when the query timeout is {@link
 * #MARKER}, which no other test sets, so the tests of other settings never see it. Listed in this
 * module's test {@code META-INF/spring.factories}, as a service lists its own.
 */
public class ReportsPoolCustomizer implements DataPropertiesCustomizer {

  /** The query timeout that asks for the pool. */
  public static final Duration MARKER = Duration.ofMillis(4321);

  /** The pool's name. */
  public static final String REPORTS = "reports";

  /** The pool's HikariCP maximum, which the Java settings set. */
  public static final String REPORTS_MAXIMUM = "3";

  @Override
  public DataProperties customize(DataProperties settings) {
    return MARKER.equals(settings.queryTimeout())
        ? settings.toBuilder().sources(Map.of(REPORTS, reportsPool())).build()
        : settings;
  }

  /** Returns the pool's Java settings. */
  private PoolSettings reportsPool() {
    return PoolSettings.builder()
        .url(NamedPoolTestData.URL)
        .hikari(Map.of("maximum-pool-size", REPORTS_MAXIMUM))
        .build();
  }
}
