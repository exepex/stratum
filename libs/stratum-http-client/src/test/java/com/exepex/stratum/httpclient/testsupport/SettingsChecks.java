package com.exepex.stratum.httpclient.testsupport;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import java.util.Arrays;
import lombok.experimental.UtilityClass;
import org.springframework.mock.env.MockEnvironment;

/** Set-up and checks of the HTTP client settings that several test classes share. */
@UtilityClass
public class SettingsChecks {

  /**
   * Returns an environment that holds the settings.
   *
   * @param settings each as {@code key=value}
   * @return the environment
   */
  public static MockEnvironment environmentOf(String... settings) {
    var environment = new MockEnvironment();
    Arrays.stream(settings)
        .map(setting -> setting.split("=", 2))
        .forEach(keyAndValue -> environment.setProperty(keyAndValue[0], keyAndValue[1]));
    return environment;
  }

  /**
   * Checks that settings stop startup with {@code SX-CORE-1003} and a message that names only the
   * broken rule, never a value.
   *
   * @param rule the rule's name after {@code stratum.http-client.}, such as {@code
   *     max-retries-in-range}
   * @param settings each as {@code key=value}
   */
  public static void assertSettingsInvalid(String rule, String... settings) {
    var environment = environmentOf(settings);

    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(() -> HttpClientProperties.resolve(environment))
        .withMessage(
            HttpClientTestData.INVALID_SETTINGS
                + HttpClientConstants.PREFIX
                + "."
                + rule
                + " breaks @AssertTrue");
  }
}
