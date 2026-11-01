package com.exepex.stratum.core.executor;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.context.ContextExecutorService;
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
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableScheduling;
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

  /** The name of the executor Spring Boot makes, and Stratum makes in its place. */
  private static final String APPLICATION_TASK_EXECUTOR = "applicationTaskExecutor";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withPropertyValues(PROPAGATE_TASK_CONTEXT)
          .withConfiguration(
              AutoConfigurations.of(
                  TaskExecutionAutoConfiguration.class,
                  TaskSchedulingAutoConfiguration.class,
                  ModuleAutoConfiguration.class));

  @Test
  @DisplayName(
      "a pool declared as Executor, ExecutorService or ScheduledExecutorService is wrapped once;"
          + " one declared as ThreadPoolExecutor is left alone, so code injecting it still starts")
  void wrapsOnlyBeansDeclaredAsTheInterfaces() {
    contextRunner
        .withUserConfiguration(ServicePools.class)
        .run(
            context -> {
              assertThat(context.getBean("invoiceRenderingPool"))
                  .isInstanceOf(ContextExecutorService.class);
              assertThat(context.getBean("reminderScheduler"))
                  .isInstanceOf(OneShotContextScheduledExecutorService.class);
              assertThat(context.getBean("auditPool")).isInstanceOf(ContextExecutorService.class);
              assertThat(context.getBean("reportingPool")).isInstanceOf(ThreadPoolExecutor.class);
              assertThat(context.getBean("prewrappedPool"))
                  .isSameAs(context.getBean(ServicePools.class).poolWrappedByService);
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
      "when the service's only executors are its own pools, @Async gets the pool Spring Boot"
          + " would have made")
  void addsAsyncPoolWhenServiceHasOnlyExecutorService() {
    contextRunner
        .withUserConfiguration(ServicePools.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(TaskExecutor.class);
              assertThat(context.getBean(AsyncConfigurer.class).getAsyncExecutor())
                  .isInstanceOf(ThreadPoolTaskExecutor.class)
                  .isSameAs(context.getBean(APPLICATION_TASK_EXECUTOR));
            });
  }

  @Test
  @DisplayName(
      "with @EnableScheduling, @Async still gets that pool, not the @Scheduled jobs' scheduler")
  void addsAsyncPoolNextToScheduler() {
    contextRunner
        .withUserConfiguration(SinglePoolService.class, SchedulingEnabled.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(TaskScheduler.class);
              assertThat(context.getBean(AsyncConfigurer.class).getAsyncExecutor())
                  .isSameAs(context.getBean(APPLICATION_TASK_EXECUTOR));
            });
  }

  @Test
  @DisplayName(
      "code that injects an Executor by type still gets the service's only pool, not Stratum's")
  void serviceCodeStillGetsItsOwnPoolByType() {
    contextRunner
        .withUserConfiguration(SinglePoolService.class)
        .run(
            context ->
                assertThat(context.getBean(Executor.class))
                    .isSameAs(context.getBean("invoiceRenderingPool")));
  }

  @Test
  @DisplayName("on virtual threads, the pool @Async gets is Spring Boot's virtual-thread executor")
  void addsVirtualThreadExecutorOnVirtualThreads() {
    contextRunner
        .withPropertyValues("spring.threads.virtual.enabled=true")
        .withUserConfiguration(ServicePools.class)
        .run(
            context ->
                assertThat(context.getBean(APPLICATION_TASK_EXECUTOR))
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
        .run(context -> assertThat(context).doesNotHaveBean(APPLICATION_TASK_EXECUTOR));
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
    assertThat(context).doesNotHaveBean(APPLICATION_TASK_EXECUTOR);
    assertThat(context).doesNotHaveBean(TaskExecutor.class);
  }

  /** Imports the configuration in the order the modules use. */
  @AutoConfiguration(
      after = TaskExecutionAutoConfiguration.class,
      before = TaskSchedulingAutoConfiguration.class)
  @Import(ContextCarryingExecutorsConfiguration.class)
  static class ModuleAutoConfiguration {}

  /** Pools a service declares, each as a different type. */
  @Configuration(proxyBeanMethods = false)
  static class ServicePools {

    /** A pool the service wraps itself, which must not be wrapped again. */
    final ExecutorService poolWrappedByService =
        ContextExecutorService.wrap(Executors.newSingleThreadExecutor());

    /**
     * Returns the pool the service wrapped itself.
     *
     * @return the pool
     */
    @Bean
    ExecutorService prewrappedPool() {
      return poolWrappedByService;
    }

    /**
     * Returns a pool declared as {@code Executor}.
     *
     * @return the pool
     */
    @Bean
    Executor auditPool() {
      return Executors.newSingleThreadExecutor();
    }

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

  /** Turns on {@code @Scheduled} jobs, which brings Spring Boot's scheduler. */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  static class SchedulingEnabled {}

  /** A service with one pool of its own. */
  @Configuration(proxyBeanMethods = false)
  static class SinglePoolService {

    /**
     * Returns the service's pool.
     *
     * @return the pool
     */
    @Bean
    ExecutorService invoiceRenderingPool() {
      return Executors.newSingleThreadExecutor();
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
