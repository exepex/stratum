package com.exepex.stratum.restclient.testsupport.timed;

import com.exepex.stratum.httpclient.HttpClientConstants;
import java.time.Duration;
import lombok.experimental.UtilityClass;

/** The names, keys and times of the tests of an operation's own timeouts. */
@UtilityClass
public class TimedTestData {

  /** The reports client's name. */
  public static final String REPORTS = "reports";

  /** The start of the reports client's settings. */
  public static final String REPORTS_SETTINGS = HttpClientConstants.CLIENTS + "." + REPORTS;

  /** The reports client's address. */
  public static final String REPORTS_URL = REPORTS_SETTINGS + ".url";

  /** The operation that waits long for a monthly report. */
  public static final String MONTHLY_REPORT = "monthly-report";

  /** The operation that must answer fast. */
  public static final String QUICK_CHECK = "quick-check";

  /** The operation that downloads a report as a stream. */
  public static final String EXPORT = "export";

  /** The path every method of the reports client calls. */
  public static final String REPORT_PATH = "/report";

  /** A wait longer than the client's timeouts and shorter than the long operations'. */
  public static final Duration SLOW_ANSWER = Duration.ofMillis(900);

  /** A wait longer than the quick check's read-timeout of 100ms, shorter than the client's. */
  public static final Duration FAIRLY_FAST_ANSWER = Duration.ofMillis(200);

  /**
   * The settings of the reports client: no retries, a read-timeout of 300ms and a total-timeout of
   * 600ms of its own, read and total timeouts of 3s and 4s for the monthly report, 3s for the
   * export, and a read-timeout of 100ms for the quick check.
   */
  public static final String SETTINGS = "classpath:timed.properties";
}
