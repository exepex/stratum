package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;

@DisplayName("RestClientEnvironmentPostProcessor sees the configuration files' values")
class RestClientEnvironmentPostProcessorTest {

  @Test
  @DisplayName(
      "it runs right after the configuration files are read, so a module switch in them counts")
  void runsAfterConfigurationFiles() {
    assertThat(new RestClientEnvironmentPostProcessor().getOrder())
        .isEqualTo(ConfigDataEnvironmentPostProcessor.ORDER + 1);
  }
}
