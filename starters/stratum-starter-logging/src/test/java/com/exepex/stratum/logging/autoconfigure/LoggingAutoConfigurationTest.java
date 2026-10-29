package com.exepex.stratum.logging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.workflow.ReactiveWorkflows;
import com.exepex.stratum.logging.workflow.WorkflowAspect;
import com.exepex.stratum.logging.workflow.Workflows;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.aspectj.weaver.Advice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@DisplayName("LoggingAutoConfiguration creates the workflow beans")
class LoggingAutoConfigurationTest {

  private static final String MODULE_ON = LoggingConstants.ENABLED + "=true";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(LoggingAutoConfiguration.class));

  @Test
  @DisplayName("with the module on, the workflows, reactive workflows and aspect are created")
  void createsBeans() {
    contextRunner
        .withPropertyValues(MODULE_ON)
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(Workflows.class)
                    .hasSingleBean(ReactiveWorkflows.class)
                    .hasSingleBean(WorkflowAspect.class));
  }

  @Test
  @DisplayName("with the module unset or off, nothing is created")
  void moduleOffCreatesNothing() {
    contextRunner.run(context -> assertThat(context).doesNotHaveBean(Workflows.class));
    contextRunner
        .withPropertyValues(LoggingConstants.ENABLED + "=false")
        .run(
            context ->
                assertThat(context)
                    .doesNotHaveBean(Workflows.class)
                    .doesNotHaveBean(WorkflowAspect.class));
  }

  @Test
  @DisplayName("without AspectJ, there is no aspect but the workflows still work")
  void withoutAspectjCreatesNoAspect() {
    contextRunner
        .withPropertyValues(MODULE_ON)
        .withClassLoader(new FilteredClassLoader(Advice.class))
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(Workflows.class)
                    .doesNotHaveBean(WorkflowAspect.class));
  }

  @Test
  @DisplayName("without Reactor, there are no reactive workflows but the aspect still works")
  void withoutReactorCreatesNoReactiveWorkflows() {
    contextRunner
        .withPropertyValues(MODULE_ON)
        .withClassLoader(new FilteredClassLoader(Mono.class))
        .run(
            context ->
                assertThat(context)
                    .doesNotHaveBean(ReactiveWorkflows.class)
                    .hasSingleBean(WorkflowAspect.class));
  }

  @Test
  @DisplayName("with an ObservationRegistry, each workflow is observed in it")
  void usesServiceRegistry() {
    var observationRegistry = ObservationRegistry.create();
    observationRegistry.observationConfig().observationHandler(context -> true);

    contextRunner
        .withPropertyValues(MODULE_ON)
        .withBean(ObservationRegistry.class, () -> observationRegistry)
        .run(
            context ->
                assertThat(
                        context
                            .getBean(Workflows.class)
                            .call(
                                "ORDER_CHECKOUT",
                                () -> currentObservationName(observationRegistry)))
                    .isEqualTo(LoggingConstants.WORKFLOW_OBSERVATION));
  }

  /** Returns the name of the observation running now in the registry. */
  private String currentObservationName(ObservationRegistry observationRegistry) {
    return Objects.requireNonNull(observationRegistry.getCurrentObservation())
        .getContext()
        .getName();
  }

  @Test
  @DisplayName("the service's own beans replace Stratum's")
  void serviceBeansWin() {
    contextRunner
        .withPropertyValues(MODULE_ON)
        .withUserConfiguration(ServiceBeansConfiguration.class)
        .run(
            context -> {
              var serviceBeans = context.getBean(ServiceBeansConfiguration.class);
              assertThat(context.getBean(Workflows.class)).isSameAs(serviceBeans.workflows);
              assertThat(context.getBean(WorkflowAspect.class)).isSameAs(serviceBeans.aspect);
              assertThat(context.getBean(ReactiveWorkflows.class))
                  .isSameAs(serviceBeans.reactiveWorkflows);
            });
  }

  @Test
  @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
  void companyBeansWin() {
    new ApplicationContextRunner()
        .withPropertyValues(MODULE_ON)
        .withConfiguration(
            AutoConfigurations.of(
                LoggingAutoConfiguration.class, CompanyBeansAutoConfiguration.class))
        .run(
            context -> {
              assertThat(context.getBean(Workflows.class))
                  .isSameAs(CompanyBeansAutoConfiguration.WORKFLOWS);
              assertThat(context.getBean(WorkflowAspect.class))
                  .isSameAs(CompanyBeansAutoConfiguration.ASPECT);
              assertThat(context.getBean(ReactiveWorkflows.class))
                  .isSameAs(CompanyBeansAutoConfiguration.REACTIVE_WORKFLOWS);
            });
  }

  /** A service's configuration with its own beans. */
  @Configuration(proxyBeanMethods = false)
  static class ServiceBeansConfiguration {

    /** The service's workflows. */
    final Workflows workflows = new Workflows(ObservationRegistry.NOOP);

    /** The service's reactive workflows. */
    final ReactiveWorkflows reactiveWorkflows = new ReactiveWorkflows(workflows);

    /** The service's aspect. */
    final WorkflowAspect aspect = new WorkflowAspect(workflows, reactiveWorkflows);

    /**
     * Returns the service's workflows.
     *
     * @return the workflows
     */
    @Bean
    Workflows serviceWorkflows() {
      return workflows;
    }

    /**
     * Returns the service's aspect.
     *
     * @return the aspect
     */
    @Bean
    WorkflowAspect serviceWorkflowAspect() {
      return aspect;
    }

    /**
     * Returns the service's reactive workflows.
     *
     * @return the reactive workflows
     */
    @Bean
    ReactiveWorkflows serviceReactiveWorkflows() {
      return reactiveWorkflows;
    }
  }

  /** A company framework's auto-configuration that declares no order. */
  @AutoConfiguration
  static class CompanyBeansAutoConfiguration {

    /** The company's workflows. */
    static final Workflows WORKFLOWS = new Workflows(ObservationRegistry.NOOP);

    /** The company's reactive workflows. */
    static final ReactiveWorkflows REACTIVE_WORKFLOWS = new ReactiveWorkflows(WORKFLOWS);

    /** The company's aspect. */
    static final WorkflowAspect ASPECT = new WorkflowAspect(WORKFLOWS, REACTIVE_WORKFLOWS);

    /**
     * Returns the company's workflows.
     *
     * @return the workflows
     */
    @Bean
    Workflows companyWorkflows() {
      return WORKFLOWS;
    }

    /**
     * Returns the company's aspect.
     *
     * @return the aspect
     */
    @Bean
    WorkflowAspect companyWorkflowAspect() {
      return ASPECT;
    }

    /**
     * Returns the company's reactive workflows.
     *
     * @return the reactive workflows
     */
    @Bean
    ReactiveWorkflows companyReactiveWorkflows() {
      return REACTIVE_WORKFLOWS;
    }
  }
}
