package com.exepex.stratum.cache.testsupport;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;

/**
 * A service whose reports, of a length the caller picks, are cached under {@value #REPORTS}, so
 * that the tests can write keys and values of any size. It counts how often it builds one.
 */
public class ReportCatalog {

  /** The name of the cache that holds the reports. */
  public static final String REPORTS = "reports";

  /** The name of the cache that holds the reports built with {@code sync=true}. */
  public static final String SYNCED_REPORTS = "synced-reports";

  /** How many times a report was really built, rather than read from the cache. */
  private final AtomicInteger buildCount = new AtomicInteger();

  /**
   * Builds a report, or returns it from the cache, cached by its id only.
   *
   * @param id the report's id, which may be of any length
   * @param length the report's length in characters
   * @return the report
   */
  @Cacheable(cacheNames = REPORTS, key = "#id")
  public String reportOf(String id, int length) {
    return build(length);
  }

  /**
   * Builds a report with {@code sync=true}, or returns it from the cache.
   *
   * @param id the report's id
   * @param length the report's length in characters
   * @return the report
   */
  @Cacheable(cacheNames = SYNCED_REPORTS, key = "#id", sync = true)
  public String syncedReportOf(String id, int length) {
    return build(length);
  }

  /**
   * Builds a report again and replaces the cached one.
   *
   * @param id the report's id
   * @param length the new report's length in characters
   * @return the report
   */
  @CachePut(cacheNames = REPORTS, key = "#id")
  public String rebuildReport(String id, int length) {
    return build(length);
  }

  /** Removes every report from the cache. */
  @CacheEvict(cacheNames = REPORTS, allEntries = true)
  public void forgetAllReports() {
    // The annotation does the work.
  }

  /**
   * Returns how many times a report was really built.
   *
   * @return the number of builds
   */
  public int builds() {
    return buildCount.get();
  }

  /** Builds a report of the given length and counts it. */
  private String build(int length) {
    buildCount.incrementAndGet();
    return "r".repeat(length);
  }
}
