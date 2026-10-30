package com.exepex.stratum.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.observability.ObservabilityConstants;
import com.exepex.stratum.observability.linkedwork.LinkedWork;
import com.exepex.stratum.observability.linkedwork.LinkedWorkTracingHandler;
import com.exepex.stratum.observability.linkedwork.ReactiveLinkedWork;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@DisplayName("ObservabilityAutoConfiguration creates the linked-work helpers, and each backs off")
class LinkedWorkAutoConfigurationTest {

  private static final String MODULE_ON = ObservabilityConstants.ENABLED + "=true";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ValidationAutoConfiguration.class, ObservabilityAutoConfiguration.class));

  @Test
  @DisplayName("with the module on, the handler and both helpers are created")
  void createsLinkedWorkBeans() {
    contextRunner
        .withPropertyValues(MODULE_ON)
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(LinkedWorkTracingHandler.class)
                    .hasSingleBean(LinkedWork.class)
                    .hasSingleBean(ReactiveLinkedWork.class));
  }

  @Test
  @DisplayName("with the module unset or off, none of them is created")
  void moduleOffCreatesNoLinkedWorkBeans() {
    contextRunner.run(context -> assertThat(context).doesNotHaveBean(LinkedWork.class));
    contextRunner
        .withPropertyValues(ObservabilityConstants.ENABLED + "=false")
        .run(
            context ->
                assertThat(context)
                    .doesNotHaveBean(LinkedWorkTracingHandler.class)
                    .doesNotHaveBean(LinkedWork.class)
                    .doesNotHaveBean(ReactiveLinkedWork.class));
  }

  @Test
  @DisplayName("without Reactor, the blocking helper is still created but the reactive one is not")
  void withoutReactorCreatesNoReactiveHelper() {
    contextRunner
        .withPropertyValues(MODULE_ON)
        .withClassLoader(new FilteredClassLoader(Mono.class))
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(LinkedWork.class)
                    .doesNotHaveBean(ReactiveLinkedWork.class));
  }

  @Test
  @DisplayName("the service's own handler and helpers replace Stratum's")
  void serviceLinkedWorkBeansWin() {
    contextRunner
        .withPropertyValues(MODULE_ON)
        .withUserConfiguration(ServiceLinkedWorkBeans.class)
        .run(
            context -> {
              assertThat(context)
                  .hasSingleBean(LinkedWorkTracingHandler.class)
                  .hasSingleBean(LinkedWork.class)
                  .hasSingleBean(ReactiveLinkedWork.class);
              assertThat(context.getBean(LinkedWorkTracingHandler.class))
                  .isSameAs(ServiceLinkedWorkBeans.HANDLER);
              assertThat(context.getBean(LinkedWork.class)).isSameAs(ServiceLinkedWorkBeans.HELPER);
              assertThat(context.getBean(ReactiveLinkedWork.class))
                  .isSameAs(ServiceLinkedWorkBeans.REACTIVE_HELPER);
            });
  }

  @Test
  @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
  void companyLinkedWorkBeansWin() {
    new ApplicationContextRunner()
        .withPropertyValues(MODULE_ON)
        .withConfiguration(
            AutoConfigurations.of(
                ValidationAutoConfiguration.class,
                ObservabilityAutoConfiguration.class,
                CompanyLinkedWorkAutoConfiguration.class))
        .run(
            context -> {
              assertThat(context)
                  .hasSingleBean(LinkedWorkTracingHandler.class)
                  .hasSingleBean(LinkedWork.class)
                  .hasSingleBean(ReactiveLinkedWork.class);
              assertThat(context.getBean(LinkedWorkTracingHandler.class))
                  .isSameAs(ServiceLinkedWorkBeans.HANDLER);
              assertThat(context.getBean(LinkedWork.class)).isSameAs(ServiceLinkedWorkBeans.HELPER);
              assertThat(context.getBean(ReactiveLinkedWork.class))
                  .isSameAs(ServiceLinkedWorkBeans.REACTIVE_HELPER);
            });
  }

  /** The service's own linked-work beans. */
  @Configuration(proxyBeanMethods = false)
  static class ServiceLinkedWorkBeans {

    /** The service's handler. */
    static final LinkedWorkTracingHandler HANDLER = new LinkedWorkTracingHandler(Tracer.NOOP);

    /** The service's blocking helper. */
    static final LinkedWork HELPER = new LinkedWork(ObservationRegistry.NOOP, Tracer.NOOP);

    /** The service's reactive helper. */
    static final ReactiveLinkedWork REACTIVE_HELPER = new ReactiveLinkedWork(HELPER);

    /**
     * Returns the service's handler.
     *
     * @return the handler
     */
    @Bean
    LinkedWorkTracingHandler serviceLinkedWorkTracingHandler() {
      return HANDLER;
    }

    /**
     * Returns the service's blocking helper.
     *
     * @return the helper
     */
    @Bean
    LinkedWork serviceLinkedWork() {
      return HELPER;
    }

    /**
     * Returns the service's reactive helper.
     *
     * @return the helper
     */
    @Bean
    ReactiveLinkedWork serviceReactiveLinkedWork() {
      return REACTIVE_HELPER;
    }
  }

  /** A company framework's auto-configuration that declares no order, with the same beans. */
  @AutoConfiguration
  static class CompanyLinkedWorkAutoConfiguration extends ServiceLinkedWorkBeans {}
}
