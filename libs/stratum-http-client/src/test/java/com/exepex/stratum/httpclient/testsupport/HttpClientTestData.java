package com.exepex.stratum.httpclient.testsupport;

import com.exepex.stratum.httpclient.HttpClientConstants;
import lombok.experimental.UtilityClass;
import org.springframework.mock.env.MockEnvironment;

/** Environments shared by the HTTP client module's tests. */
@UtilityClass
public class HttpClientTestData {

  /**
   * Returns an environment with {@code stratum.http-client.enabled=true} and nothing else set.
   *
   * @return the environment
   */
  public static MockEnvironment environmentWithModuleOn() {
    return new MockEnvironment().withProperty(HttpClientConstants.ENABLED, "true");
  }
}
