package com.exepex.stratum.core.executor;

import com.exepex.stratum.core.condition.StratumModuleConstants;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnThreading;
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.boot.thread.Threading;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Makes the service's own thread pools carry the logging and tracing context. Imported by the
 * logging and observability auto-configurations, so it applies while either module is on, and
 * evaluated after Spring Boot's task execution auto-configuration and before its task scheduling.
 */
@Configuration(proxyBeanMethods = false)
public class ContextCarryingExecutorsConfiguration {

  /**
   * Wraps the pools the service declares as beans so their tasks keep the context, unless {@code
   * stratum.context-propagation.executor-beans=false}. Static, because Spring creates bean
   * post-processors before the other beans.
   *
   * @param beanFactory the service's beans
   * @return the post-processor that wraps them
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  @ConditionalOnBooleanProperty(
      name = StratumModuleConstants.CONTEXT_PROPAGATION_EXECUTOR_BEANS,
      matchIfMissing = true)
  public static ContextCarryingExecutorServiceBeans contextCarryingExecutorServiceBeans(
      ConfigurableListableBeanFactory beanFactory) {
    return new ContextCarryingExecutorServiceBeans(beanFactory);
  }

  /**
   * Gives {@code @Async} methods the executor Spring Boot would have made, when Spring Boot made
   * none because the service declares an executor of another kind, such as an {@code
   * ExecutorService} bean. Without it, {@code @Async} runs on a new thread per call that carries no
   * context, or on the {@code @Scheduled} jobs' scheduler. Not used when the service has its own
   * {@code TaskExecutor}, an executor named {@code taskExecutor} or an {@code AsyncConfigurer},
   * because {@code @Async} then uses the service's choice. The modules order this before Spring
   * Boot's task scheduling, so its scheduler does not count as the service's own task executor.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnBooleanProperty(StratumModuleConstants.PROPAGATE_TASK_CONTEXT)
  @ConditionalOnMissingBean(
      value = {TaskExecutor.class, AsyncConfigurer.class},
      name = {
        StratumModuleConstants.APPLICATION_TASK_EXECUTOR,
        StratumModuleConstants.ASYNC_TASK_EXECUTOR
      })
  static class AsyncExecutorConfiguration {

    /**
     * Returns the platform-thread pool, built with Spring Boot's builder, so it follows {@code
     * spring.task.execution.*} and carries the context. Lazy, as Spring Boot's is.
     *
     * @param builder Spring Boot's builder for platform-thread pools
     * @return the pool
     */
    @Bean(StratumModuleConstants.APPLICATION_TASK_EXECUTOR)
    @Lazy
    @Fallback
    @ConditionalOnMissingBean(name = StratumModuleConstants.APPLICATION_TASK_EXECUTOR)
    @ConditionalOnThreading(Threading.PLATFORM)
    ThreadPoolTaskExecutor applicationTaskExecutor(ThreadPoolTaskExecutorBuilder builder) {
      return builder.build();
    }

    /**
     * Returns the virtual-thread executor, built with Spring Boot's builder, when the service runs
     * on virtual threads.
     *
     * @param builder Spring Boot's builder for virtual-thread executors
     * @return the executor
     */
    @Bean(StratumModuleConstants.APPLICATION_TASK_EXECUTOR)
    @Fallback
    @ConditionalOnMissingBean(name = StratumModuleConstants.APPLICATION_TASK_EXECUTOR)
    @ConditionalOnThreading(Threading.VIRTUAL)
    SimpleAsyncTaskExecutor applicationTaskExecutorVirtualThreads(
        SimpleAsyncTaskExecutorBuilder builder) {
      return builder.build();
    }

    /**
     * Points {@code @Async} at that executor, as Spring Boot does for its own, so a task scheduler
     * that is also a task executor does not take its place.
     *
     * @param beanFactory the service's beans, read when the first {@code @Async} method runs
     * @return the configurer
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean
    AsyncConfigurer applicationTaskExecutorAsyncConfigurer(BeanFactory beanFactory) {
      return new AsyncConfigurer() {
        @Override
        public Executor getAsyncExecutor() {
          return beanFactory.getBean(
              StratumModuleConstants.APPLICATION_TASK_EXECUTOR, Executor.class);
        }
      };
    }
  }
}
