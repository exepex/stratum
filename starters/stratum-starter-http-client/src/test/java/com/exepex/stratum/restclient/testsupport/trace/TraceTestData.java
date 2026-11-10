package com.exepex.stratum.restclient.testsupport.trace;

import com.exepex.stratum.httpclient.HttpClientConstants;
import java.util.function.Supplier;
import lombok.experimental.UtilityClass;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Names and settings shared by the tests of trace headers. */
@UtilityClass
public class TraceTestData {

  /** The client of one of the service's own services, with the default trace headers. */
  public static final String TRACED = "traced";

  /** The client of a third party, set to send no trace headers. */
  public static final String THIRD_PARTY = "third-party";

  /** The prefix of the traced client's settings. */
  public static final String TRACED_SETTINGS = HttpClientConstants.CLIENTS + "." + TRACED;

  /** The prefix of the third party's settings. */
  public static final String THIRD_PARTY_SETTINGS = HttpClientConstants.CLIENTS + "." + THIRD_PARTY;

  /** The correlation ID the service's own code sets. */
  public static final String CORRELATION_ID = "checkout-777";

  /** The correlation ID a team's own code or the caller's request sets on a call. */
  public static final String TEAM_CORRELATION_ID = "team-42";

  /**
   * Points both clients at one server.
   *
   * @param registry the test's properties
   * @param url the server's address
   */
  public static void pointClientsAt(DynamicPropertyRegistry registry, Supplier<Object> url) {
    registry.add(TRACED_SETTINGS + ".url", url);
    registry.add(THIRD_PARTY_SETTINGS + ".url", url);
  }
}
