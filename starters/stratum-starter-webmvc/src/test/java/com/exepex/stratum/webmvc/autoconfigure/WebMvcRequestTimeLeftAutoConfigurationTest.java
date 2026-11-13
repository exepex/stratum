package com.exepex.stratum.webmvc.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.request.RequestTimeLeft;
import com.exepex.stratum.web.CurrentRequestTimeLeft;
import com.exepex.stratum.web.RequestDeadlineAccessor;
import com.exepex.stratum.web.RequestDeadlinePropagation;
import com.exepex.stratum.web.RequestFieldsPropagation;
import com.exepex.stratum.web.ResponseTimeouts;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.webmvc.testsupport.WebMvcRequestFieldsRunners;
import io.micrometer.context.ContextRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

@DisplayName("The web MVC starter tells the service how long the current request has left")
class WebMvcRequestTimeLeftAutoConfigurationTest {

  /** The time left a replacement reports. */
  private static final Optional<Duration> OWN_TIME_LEFT = Optional.of(Duration.ofSeconds(7));

  /** A path that matches no route, so it gets the default limit. */
  private static final String ORDERS = "/orders";

  /** A servlet application with Stratum's request fields. */
  private final WebApplicationContextRunner contextRunner =
      WebMvcRequestFieldsRunners.requestFieldsRunner();

  @Nested
  @DisplayName("when the module is switched on or off")
  class ModuleSwitch {

    @Test
    @DisplayName("with the module on, the time left and its carrying to other threads are created")
    void createsBeans() {
      contextRunner.run(
          context ->
              assertThat(context)
                  .hasSingleBean(RequestTimeLeft.class)
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
    @DisplayName("two applications in one JVM both read the request on the thread")
    void twoApplicationsInOneJvm() {
      var seen = new ArrayList<Optional<Duration>>();

      contextRunner.run(first -> contextRunner.run(second -> seen.addAll(bothRead(first, second))));

      assertThat(seen).hasSize(2).allMatch(Optional::isPresent);
    }
  }

  @Nested
  @DisplayName("carrying the deadline to other threads")
  class Carrying {

    @Test
    @DisplayName("is registered at startup, also under lazy initialization")
    void registeredUnderLazyInit() {
      ContextRegistry.getInstance()
          .removeThreadLocalAccessor(WebConstants.REQUEST_DEADLINE_CONTEXT_KEY);

      contextRunner
          .withPropertyValues("spring.main.lazy-initialization=true")
          .run(context -> assertDeadlineCarried());
    }

    @Test
    @DisplayName("stays when the service replaces how the request fields are carried")
    void independentOfRequestFields() {
      ContextRegistry.getInstance()
          .removeThreadLocalAccessor(WebConstants.REQUEST_DEADLINE_CONTEXT_KEY);

      contextRunner
          .withBean(RequestFieldsPropagation.class, NoRequestFieldsPropagation::new)
          .run(context -> assertDeadlineCarried());
    }
  }

  @Nested
  @DisplayName("when a service or framework supplies its own")
  class BackingOff {

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

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
    void companyBeanWins() {
      contextRunner
          .withConfiguration(AutoConfigurations.of(CompanyTimeLeftAutoConfiguration.class))
          .run(
              context ->
                  assertThat(context.getBean(RequestTimeLeft.class).timeLeft())
                      .isEqualTo(OWN_TIME_LEFT));
    }
  }

  /**
   * Puts a request's deadline on this thread, from the first application, and returns the time left
   * each application reads.
   */
  private List<Optional<Duration>> bothRead(ApplicationContext first, ApplicationContext second) {
    var deadlines = new RequestDeadlineAccessor();
    var deadline = first.getBean(ResponseTimeouts.class).start(ORDERS, () -> {});
    deadlines.setValue(deadline);
    try {
      return List.of(
          first.getBean(RequestTimeLeft.class).timeLeft(),
          second.getBean(RequestTimeLeft.class).timeLeft());
    } finally {
      deadline.end();
      deadlines.setValue();
    }
  }

  /** Checks that the deadline's accessor is registered with Micrometer's context propagation. */
  private void assertDeadlineCarried() {
    assertThat(ContextRegistry.getInstance().getThreadLocalAccessors())
        .anyMatch(accessor -> WebConstants.REQUEST_DEADLINE_CONTEXT_KEY.equals(accessor.key()));
  }

  /** A service's replacement that carries no request fields. */
  static class NoRequestFieldsPropagation extends RequestFieldsPropagation {

    /** Registers nothing. */
    @Override
    public void afterSingletonsInstantiated() {
      // The service carries its fields its own way.
    }
  }

  /** A company framework's auto-configuration that declares no order. */
  @AutoConfiguration
  static class CompanyTimeLeftAutoConfiguration {

    /**
     * Returns the company's time left.
     *
     * @return the time left
     */
    @Bean
    RequestTimeLeft companyRequestTimeLeft() {
      return () -> OWN_TIME_LEFT;
    }
  }
}
