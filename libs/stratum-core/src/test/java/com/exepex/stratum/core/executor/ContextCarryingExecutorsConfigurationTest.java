package com.exepex.stratum.core.executor;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextScheduledExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@DisplayName(
    "ContextCarryingExecutorsConfiguration wraps the service's ExecutorService beans and gives"
        + " @Async a pool only when the service has none")
class ContextCarryingExecutorsConfigurationTest {

  /** Spring Boot's switch that makes its executors carry the context, on as the modules set it. */
  private static final String PROPAGATE_TASK_CONTEXT =
      "spring.task.execution.propagate-context=true";

  /** The name {@code @Async} looks for when there are several task executors. */
  private static final String ASYNC_TASK_EXECUTOR = "taskExecutor";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withPropertyValues(PROPAGATE_TASK_CONTEXT)
          .withConfiguration(
              AutoConfigurations.of(
                  TaskExecutionAutoConfiguration.class, ModuleAutoConfiguration.class));

  @Test
  @DisplayName(
      "a bean declared as ExecutorService or ScheduledExecutorService is wrapped; one declared as"
          + " ThreadPoolExecutor is left alone, so code that injects that class still starts")
  void wrapsOnlyBeansDeclaredAsTheInterfaces() {
    contextRunner
        .withUserConfiguration(ServicePools.class)
        .run(
            context -> {
              assertThat(context.getBean("invoiceRenderingPool"))
                  .isInstanceOf(ContextExecutorService.class);
              assertThat(context.getBean("reminderScheduler"))
                  .isInstanceOf(ContextScheduledExecutorService.class);
              assertThat(context.getBean("reportingPool")).isInstanceOf(ThreadPoolExecutor.class);
            });
  }

  @Test
  @DisplayName("stratum.context-propagation.executor-beans=false leaves the service's beans alone")
  void leavesBeansAloneWhenSwitchedOff() {
    contextRunner
        .withPropertyValues("stratum.context-propagation.executor-beans=false")
        .withUserConfiguration(ServicePools.class)
        .run(
            context ->
                assertThat(context.getBean("invoiceRenderingPool"))
                    .isNotInstanceOf(ContextExecutorService.class));
  }

  @Test
  @DisplayName(
      "when the service's only executor is an ExecutorService, @Async gets Spring Boot's pool under"
          + " both names it looks for")
  void addsAsyncPoolWhenServiceHasOnlyExecutorService() {
    contextRunner
        .withUserConfiguration(ServicePools.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(TaskExecutor.class);
              assertThat(context.getBean(ASYNC_TASK_EXECUTOR))
                  .isInstanceOf(ThreadPoolTaskExecutor.class)
                  .isSameAs(context.getBean("applicationTaskExecutor"));
            });
  }

  @Test
  @DisplayName("on virtual threads, the pool @Async gets is Spring Boot's virtual-thread executor")
  void addsVirtualThreadExecutorOnVirtualThreads() {
    contextRunner
        .withPropertyValues("spring.threads.virtual.enabled=true")
        .withUserConfiguration(ServicePools.class)
        .run(
            context ->
                assertThat(context.getBean(ASYNC_TASK_EXECUTOR))
                    .isInstanceOf(SimpleAsyncTaskExecutor.class));
  }

  @Test
  @DisplayName(
      "no pool is added when the service has no executor, so Spring Boot's own stays the only one")
  void keepsSpringBootPoolWhenServiceHasNone() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(TaskExecutor.class);
          assertThat(context).doesNotHaveBean(ASYNC_TASK_EXECUTOR);
          assertThat(context).hasSingleBean(AsyncConfigurer.class);
        });
  }

  @Test
  @DisplayName(
      "no pool is added when the service has a TaskExecutor, an executor named taskExecutor or an"
          + " AsyncConfigurer, because @Async then uses the service's choice")
  void addsNoPoolWhenServiceChoseOne() {
    contextRunner
        .withUserConfiguration(ServiceTaskExecutor.class)
        .run(context -> assertThat(context).doesNotHaveBean("applicationTaskExecutor"));
    contextRunner
        .withUserConfiguration(ServiceExecutorNamedTaskExecutor.class)
        .run(this::assertNoPoolAdded);
    contextRunner
        .withUserConfiguration(ServicePools.class, ServiceAsyncConfigurer.class)
        .run(this::assertNoPoolAdded);
  }

  @Test
  @DisplayName("no pool is added when the service turns off Spring Boot's context propagation")
  void addsNoPoolWhenPropagationIsOff() {
    contextRunner
        .withPropertyValues("spring.task.execution.propagate-context=false")
        .withUserConfiguration(ServicePools.class)
        .run(context -> assertThat(context).doesNotHaveBean(TaskExecutor.class));
  }

  /** Checks that Stratum added no {@code @Async} pool. */
  private void assertNoPoolAdded(AssertableApplicationContext context) {
    assertThat(context).doesNotHaveBean("applicationTaskExecutor");
    assertThat(context).doesNotHaveBean(TaskExecutor.class);
  }

  /** Imports the configuration after Spring Boot's task execution, as the modules do. */
  @AutoConfiguration(after = TaskExecutionAutoConfiguration.class)
  @Import(ContextCarryingExecutorsConfiguration.class)
  static class ModuleAutoConfiguration {}

  /** Pools a service declares, each as a different type. */
  @Configuration(proxyBeanMethods = false)
  static class ServicePools {

    /**
     * Returns a pool declared as {@code ExecutorService}.
     *
     * @return the pool
     */
    @Bean
    ExecutorService invoiceRenderingPool() {
      return Executors.newSingleThreadExecutor();
    }

    /**
     * Returns a pool declared as {@code ScheduledExecutorService}.
     *
     * @return the pool
     */
    @Bean
    ScheduledExecutorService reminderScheduler() {
      return Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Returns a pool declared as its class, {@code ThreadPoolExecutor}.
     *
     * @return the pool
     */
    @Bean
    ThreadPoolExecutor reportingPool() {
      return (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
    }
  }

  /** A service that declares a {@code TaskExecutor} of its own. */
  @Configuration(proxyBeanMethods = false)
  static class ServiceTaskExecutor {

    /**
     * Returns the service's task executor.
     *
     * @return the executor
     */
    @Bean
    TaskExecutor billingTaskExecutor() {
      return new SyncTaskExecutor();
    }
  }

  /** A service that names its plain executor {@code taskExecutor}, for {@code @Async}. */
  @Configuration(proxyBeanMethods = false)
  static class ServiceExecutorNamedTaskExecutor {

    /**
     * Returns the executor {@code @Async} picks by name.
     *
     * @return the executor
     */
    @Bean(ASYNC_TASK_EXECUTOR)
    Executor taskExecutor() {
      return Executors.newSingleThreadExecutor();
    }
  }

  /** A service that tells {@code @Async} which executor to use through an AsyncConfigurer. */
  @Configuration(proxyBeanMethods = false)
  static class ServiceAsyncConfigurer {

    /**
     * Returns the configurer.
     *
     * @return the configurer
     */
    @Bean
    AsyncConfigurer billingAsyncConfigurer() {
      return new AsyncConfigurer() {};
    }
  }
}
