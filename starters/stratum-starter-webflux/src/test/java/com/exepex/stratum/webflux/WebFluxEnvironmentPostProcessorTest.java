package com.exepex.stratum.webflux;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("WebFluxEnvironmentPostProcessor limits multipart uploads in standard mode")
class WebFluxEnvironmentPostProcessorTest {

  /** Spring Boot's keys, spelled out so a misspelt key in the module fails the test. */
  private static final String DISK_PER_PART = "spring.webflux.multipart.max-disk-usage-per-part";

  private static final String PARTS = "spring.webflux.multipart.max-parts";

  private static final String WEB_ON = "stratum.web.enabled";

  private static final String BACKWARD_COMPATIBLE = "stratum.web.backward-compatible";

  private final WebFluxEnvironmentPostProcessor postProcessor =
      new WebFluxEnvironmentPostProcessor();

  /** Returns an environment with the web module on and backward-compatible mode off. */
  private MockEnvironment standardMode() {
    return new MockEnvironment()
        .withProperty(WEB_ON, "true")
        .withProperty(BACKWARD_COMPATIBLE, "false");
  }

  /** Runs the post-processor on the environment and returns it. */
  private MockEnvironment runPostProcessor(MockEnvironment environment) {
    postProcessor.postProcessEnvironment(environment, new SpringApplication());
    return environment;
  }

  @Test
  @DisplayName("sets 10MB of disk per part and 50 parts in standard mode")
  void limitsWhenModuleOn() {
    var environment = runPostProcessor(standardMode());

    assertThat(environment.getProperty(DISK_PER_PART)).isEqualTo("10MB");
    assertThat(environment.getProperty(PARTS)).isEqualTo("50");
  }

  @Test
  @DisplayName("keeps the service's own values")
  void serviceValuesWin() {
    var environment =
        runPostProcessor(
            standardMode().withProperty(DISK_PER_PART, "25MB").withProperty(PARTS, "5"));

    assertThat(environment.getProperty(DISK_PER_PART)).isEqualTo("25MB");
    assertThat(environment.getProperty(PARTS)).isEqualTo("5");
  }

  @Test
  @DisplayName("sets nothing in backward-compatible mode, so uploads that worked keep working")
  void nothingInBackwardCompatibleMode() {
    var environment = runPostProcessor(new MockEnvironment().withProperty(WEB_ON, "true"));

    assertThat(environment.getProperty(DISK_PER_PART)).isNull();
    assertThat(environment.getProperty(PARTS)).isNull();
  }

  @Test
  @DisplayName("sets nothing when the web module is off")
  void nothingWhenModuleOff() {
    var environment = runPostProcessor(new MockEnvironment());

    assertThat(environment.getProperty(DISK_PER_PART)).isNull();
    assertThat(environment.getProperty(PARTS)).isNull();
  }
}
