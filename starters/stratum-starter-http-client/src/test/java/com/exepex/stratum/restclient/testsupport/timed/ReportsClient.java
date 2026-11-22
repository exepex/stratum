package com.exepex.stratum.restclient.testsupport.timed;

import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.httpclient.StratumOperation;
import java.io.InputStream;
import org.springframework.web.service.annotation.GetExchange;

/** The reports service's client: the same path as three operations and as a plain method. */
@StratumClient(TimedTestData.REPORTS)
public interface ReportsClient {

  /**
   * Fetches the report as the monthly-report operation, with long timeouts of its own.
   *
   * @return the body
   */
  @StratumOperation(TimedTestData.MONTHLY_REPORT)
  @GetExchange(TimedTestData.REPORT_PATH)
  String monthlyReport();

  /**
   * Fetches the report as the quick-check operation, with a short read-timeout of its own.
   *
   * @return the body
   */
  @StratumOperation(TimedTestData.QUICK_CHECK)
  @GetExchange(TimedTestData.REPORT_PATH)
  String quickCheck();

  /**
   * Downloads the report as a stream, as the export operation, with a long read-timeout of its own.
   *
   * @return the body, as it arrives
   */
  @StratumOperation(TimedTestData.EXPORT)
  @GetExchange(TimedTestData.REPORT_PATH)
  InputStream export();

  /**
   * Fetches the report with the client's own timeouts.
   *
   * @return the body
   */
  @GetExchange(TimedTestData.REPORT_PATH)
  String plain();
}
