package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.exepex.stratum.httpclient.HttpClientConstants;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("RestClientEnvironmentPostProcessor sees the configuration files' values")
class RestClientEnvironmentPostProcessorTest {

  /** The status key of the code of a service token that cannot be fetched. */
  private static final String STATUS_0002 =
      HttpClientConstants.WEB_ERROR_STATUSES + "[SX-HTTPC-0002]";

  /** The status key of the code of a relay without a caller token. */
  private static final String STATUS_0004 =
      HttpClientConstants.WEB_ERROR_STATUSES + "[SX-HTTPC-0004]";

  /** The default status of every code but a timeout and a service that cannot be reached. */
  private static final String INTERNAL_ERROR = "500";

  /** The default status of a service that cannot be reached and of an open breaker. */
  private static final String UNAVAILABLE = "503";

  /**
   * Each code a call can end with and its default status: 504 for a timeout, 503 when the service
   * cannot be reached or the breaker is open, and 500 for every other, error answers included.
   */
  private static final List<Arguments> DEFAULT_STATUSES =
      List.of(
          arguments("SX-HTTPC-0001", "504"),
          arguments("SX-HTTPC-0002", UNAVAILABLE),
          arguments("SX-HTTPC-0003", UNAVAILABLE),
          arguments("SX-HTTPC-0004", INTERNAL_ERROR),
          arguments("SX-HTTPC-0005", INTERNAL_ERROR),
          arguments("SX-HTTPC-0006", INTERNAL_ERROR),
          arguments("SX-HTTPC-0007", INTERNAL_ERROR),
          arguments("SX-HTTPC-0008", INTERNAL_ERROR),
          arguments("SX-HTTPC-0009", INTERNAL_ERROR));

  @Test
  @DisplayName(
      "it runs right after the configuration files are read, so a module switch in them counts")
  void runsAfterConfigurationFiles() {
    assertThat(new RestClientEnvironmentPostProcessor().getOrder())
        .isEqualTo(ConfigDataEnvironmentPostProcessor.ORDER + 1);
  }

  @Test
  @DisplayName("each code a call can fail with gets its status, unless the service set one")
  void addsCallCodeStatuses() {
    var environment = new MockEnvironment().withProperty(STATUS_0004, "418");

    new RestClientEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty(STATUS_0002)).isEqualTo(UNAVAILABLE);
    assertThat(environment.getProperty(STATUS_0004)).isEqualTo("418");
  }

  @ParameterizedTest
  @FieldSource("DEFAULT_STATUSES")
  @DisplayName("with no status set by the service, each code gets Stratum's default")
  void defaultStatuses(String code, String status) {
    var environment = new MockEnvironment();

    new RestClientEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty(HttpClientConstants.errorStatus(code))).isEqualTo(status);
  }

  @Test
  @DisplayName("with the module off, no status is added")
  void moduleOffAddsNothing() {
    var environment = new MockEnvironment().withProperty(HttpClientConstants.ENABLED, "false");

    new RestClientEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty(STATUS_0002)).isNull();
  }
}
