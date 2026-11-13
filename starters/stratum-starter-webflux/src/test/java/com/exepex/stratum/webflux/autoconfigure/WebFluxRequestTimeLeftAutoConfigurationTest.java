package com.exepex.stratum.webflux.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.request.RequestTimeLeft;
import com.exepex.stratum.web.CurrentRequestTimeLeft;
import com.exepex.stratum.web.RequestDeadlinePropagation;
import com.exepex.stratum.web.WebConstants;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

@DisplayName("The WebFlux starter tells the service how long the current request has left")
class WebFluxRequestTimeLeftAutoConfigurationTest {

  /** The time left a replacement reports. */
  private static final Optional<Duration> OWN_TIME_LEFT = Optional.of(Duration.ofSeconds(7));

  /** A reactive application with Stratum's request fields. */
  private final ReactiveWebApplicationContextRunner contextRunner =
      new ReactiveWebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(WebFluxRequestFieldsAutoConfiguration.class));

  @Test
  @DisplayName("with the module on, the time left and its carrying to other threads are created")
  void createsBeans() {
    contextRunner.run(
        context ->
            assertThat(context)
                .hasSingleBean(RequestDeadlinePropagation.class)
                .getBean(RequestTimeLeft.class)
                .isInstanceOf(CurrentRequestTimeLeft.class));
  }

  @Test
  @DisplayName("with the module off, neither is created, so there is no request limit")
  void moduleOffCreatesNothing() {
    contextRunner
        .withPropertyValues(WebConstants.ENABLED + "=false")
        .run(
            context ->
                assertThat(context)
                    .doesNotHaveBean(RequestTimeLeft.class)
                    .doesNotHaveBean(RequestDeadlinePropagation.class));
  }

  @Test
  @DisplayName("the service's own time left wins")
  void serviceBeanWins() {
    contextRunner
        .withBean(RequestTimeLeft.class, () -> () -> OWN_TIME_LEFT)
        .run(
            context ->
                assertThat(context.getBean(RequestTimeLeft.class).timeLeft())
                    .isEqualTo(OWN_TIME_LEFT));
  }
}
