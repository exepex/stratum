package com.exepex.stratum.resilience.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.WebTestSupport;
import java.net.http.HttpResponse;
import lombok.experimental.UtilityClass;

/** Checks of a refused request's answer, shared by the integration tests. */
@UtilityClass
public class RefusalAssertions {

  /** The header that tells a refused caller how long to wait. */
  public static final String RETRY_AFTER = "Retry-After";

  /**
   * Checks a refusal answered by the web starter: the status, Stratum's body with {@code
   * SX-RESILIENCE-0001} and one {@code Retry-After} header.
   *
   * @param response the answer
   * @param status the status it must have
   * @param retryAfterSeconds the value its {@code Retry-After} header must have
   */
  public static void checkRefused(
      HttpResponse<String> response, int status, String retryAfterSeconds) {
    WebTestSupport.checkProblemDetails(response, status, ResilienceTestData.REFUSED_CODE);
    checkRetryAfter(response, retryAfterSeconds);
  }

  /**
   * Checks that the answer has one {@code Retry-After} header with the value.
   *
   * @param response the answer
   * @param retryAfterSeconds the value it must have
   */
  public static void checkRetryAfter(HttpResponse<String> response, String retryAfterSeconds) {
    assertThat(response.headers().allValues(RETRY_AFTER)).containsExactly(retryAfterSeconds);
  }
}
