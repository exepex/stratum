package com.exepex.stratum.restclient.testsupport.answers;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.LargeAnswerDownstream;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import java.time.Duration;
import lombok.experimental.UtilityClass;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/** Checks of calls whose answers pass the limit, shared by the tests of the body limits. */
@UtilityClass
public class AnswerChecks {

  /** The answers client's {@code response-body-limit} in the tests: 16 KB. */
  public static final int LIMIT = 16 * 1024;

  /** The setting that gives the answers client {@link #LIMIT}. */
  public static final String LIMIT_SETTING =
      RestClientTestData.INVENTORY_SETTINGS + ".response-body-limit=16KB";

  /** The setting that gives the answers client a pool of one connection. */
  public static final String POOL_OF_ONE =
      RestClientTestData.INVENTORY_SETTINGS + ".max-connections=1";

  /** The setting that makes a call fail fast when that connection is still busy. */
  public static final String SHORT_MAX_WAIT =
      RestClientTestData.INVENTORY_SETTINGS + ".max-wait=300ms";

  /**
   * Checks that a call fails with {@code SX-HTTPC-0006} well before the client's timeout.
   *
   * @param call the call
   */
  public static void assertTooLarge(ThrowingCallable call) {
    assertQuick(() -> CallChecks.assertEndsWith(call, HttpClientErrorCode.RESPONSE_TOO_LARGE));
  }

  /**
   * Checks that a check, such as a call and its result, ends well before the client's timeout.
   *
   * @param check the check
   */
  public static void assertQuick(Runnable check) {
    var startedAt = System.nanoTime();
    check.run();
    assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
        .isLessThan(RestClientTestData.FAILS_WITHIN);
  }

  /**
   * Returns how many connections a check's call left the answers client to open, as {@link
   * CallChecks#connectionsOpenedAfter} counts them, with a small call around it.
   *
   * @param client the answers client
   * @param downstream its server
   * @param check the check of the call
   * @return the number of connections opened after the check's call
   */
  public static int connectionsOpenedAfter(
      AnswersClient client, LargeAnswerDownstream downstream, Runnable check) {
    return CallChecks.connectionsOpenedAfter(downstream::connections, client::small, check);
  }
}
