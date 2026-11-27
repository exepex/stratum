package com.exepex.stratum.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.web.WebConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("ResilienceEnvironmentPostProcessor gives a refused request its default status")
class ResilienceEnvironmentPostProcessorTest {

  /** The status key of a refused request, as Stratum's defaults write a map key. */
  private static final String REFUSED_STATUS = WebConstants.ERROR_STATUSES + "[SX-RESILIENCE-0001]";

  /** Returns the status the environment holds for a refused request after the post-processor. */
  private String statusAfterPostProcessing(MockEnvironment environment) {
    new ResilienceEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication());
    return environment.getProperty(REFUSED_STATUS);
  }

  @Test
  @DisplayName("it runs right after the configuration files are read, so their values count")
  void runsAfterConfigurationFiles() {
    assertThat(new ResilienceEnvironmentPostProcessor().getOrder())
        .isEqualTo(ConfigDataEnvironmentPostProcessor.ORDER + 1);
  }

  @Test
  @DisplayName("with nothing set, SX-RESILIENCE-0001 gets 503")
  void addsDefault() {
    assertThat(statusAfterPostProcessing(new MockEnvironment())).isEqualTo("503");
  }

  @Test
  @DisplayName("a status the service sets, such as 429, wins")
  void serviceValueWins() {
    var environment = new MockEnvironment().withProperty(REFUSED_STATUS, "429");

    assertThat(statusAfterPostProcessing(environment)).isEqualTo("429");
  }

  @Test
  @DisplayName("with the module off, no status is added")
  void moduleOffAddsNothing() {
    var environment = new MockEnvironment().withProperty(ResilienceConstants.ENABLED, "false");

    assertThat(statusAfterPostProcessing(environment)).isNull();
  }
}
