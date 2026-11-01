package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.testsupport.HttpClientTestData;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("HttpClientEnvironmentPostProcessor gives Spring Boot's clients timeouts")
class HttpClientEnvironmentPostProcessorTest {

  /** Spring Boot's key, spelled out so a misspelt key in the module fails the test. */
  private static final String CONNECT_TIMEOUT = "spring.http.clients.connect-timeout";

  /** Spring Boot's key, spelled out so a misspelt key in the module fails the test. */
  private static final String READ_TIMEOUT = "spring.http.clients.read-timeout";

  /** Stratum's defaults, spelled out so a changed default fails the test. */
  private static final Duration CONNECT_WITHIN = Duration.ofSeconds(2);

  private static final Duration READ_WITHIN = Duration.ofSeconds(5);

  private final HttpClientEnvironmentPostProcessor postProcessor =
      new HttpClientEnvironmentPostProcessor();

  /** Runs the post-processor on the environment and returns it. */
  private MockEnvironment runPostProcessor(MockEnvironment environment) {
    postProcessor.postProcessEnvironment(environment, new SpringApplication());
    return environment;
  }

  @Test
  @DisplayName("with the module on, every client connects within 2s and reads within 5s")
  void setsTimeouts() {
    var environment = runPostProcessor(HttpClientTestData.environmentWithModuleOn());

    assertThat(environment.getProperty(CONNECT_TIMEOUT, Duration.class)).isEqualTo(CONNECT_WITHIN);
    assertThat(environment.getProperty(READ_TIMEOUT, Duration.class)).isEqualTo(READ_WITHIN);
  }

  @Test
  @DisplayName("a timeout the service sets wins")
  void serviceTimeoutWins() {
    var environment =
        runPostProcessor(
            HttpClientTestData.environmentWithModuleOn().withProperty(READ_TIMEOUT, "30s"));

    assertThat(environment.getProperty(READ_TIMEOUT)).isEqualTo("30s");
    assertThat(environment.getProperty(CONNECT_TIMEOUT, Duration.class)).isEqualTo(CONNECT_WITHIN);
  }

  @Test
  @DisplayName("with the module unset or off, Spring Boot's clients keep its own defaults")
  void leavesBootDefaultsWhenOff() {
    var unset = runPostProcessor(new MockEnvironment());
    var off =
        runPostProcessor(new MockEnvironment().withProperty(HttpClientConstants.ENABLED, "false"));

    assertThat(unset.getProperty(READ_TIMEOUT)).isNull();
    assertThat(off.getProperty(CONNECT_TIMEOUT)).isNull();
  }
}
