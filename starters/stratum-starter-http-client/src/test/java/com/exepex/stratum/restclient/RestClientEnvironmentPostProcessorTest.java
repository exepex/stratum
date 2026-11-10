package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.HttpClientConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

    assertThat(environment.getProperty(STATUS_0002)).isEqualTo("503");
    assertThat(environment.getProperty(STATUS_0004)).isEqualTo("418");
  }

  @Test
  @DisplayName("with no status set by the service, each code gets Stratum's default")
  void defaultStatuses() {
    var environment = new MockEnvironment();

    new RestClientEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty(HttpClientConstants.errorStatus("SX-HTTPC-0001")))
        .isEqualTo("504");
    assertThat(environment.getProperty(STATUS_0002)).isEqualTo("503");
    assertThat(environment.getProperty(HttpClientConstants.errorStatus("SX-HTTPC-0003")))
        .isEqualTo("503");
    assertThat(environment.getProperty(STATUS_0004)).isEqualTo("500");
    assertThat(environment.getProperty(HttpClientConstants.errorStatus("SX-HTTPC-0005")))
        .isEqualTo("500");
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
