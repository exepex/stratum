package com.exepex.stratum.core.executor;

import com.exepex.stratum.core.condition.StratumModuleConstants;
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
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Makes the service's own thread pools carry the logging and tracing context. Imported by the
 * logging and observability auto-configurations, so it applies while either module is on, and
 * evaluated after Spring Boot's task execution auto-configuration.
 */
@Configuration(proxyBeanMethods = false)
public class ContextCarryingExecutorsConfiguration {

  /**
   * Wraps the service's own {@code ExecutorService} beans so their tasks keep the context, unless
   * {@code stratum.context-propagation.executor-beans=false}. Static, because Spring creates bean
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
   * Gives {@code @Async} methods the pool Spring Boot would have made, when Spring Boot made none
   * because the service declares an executor of another kind. Without it, {@code @Async} runs on a
   * new thread per call that carries no context. Built with Spring Boot's builder, so it follows
   * {@code spring.task.execution.*} and carries the context. Also named {@code taskExecutor}, so
   * {@code @Async} still picks it when a task scheduler is a second task executor.
   *
   * @param builder Spring Boot's builder for platform-thread pools
   * @return the pool
   */
  @Bean({
    StratumModuleConstants.APPLICATION_TASK_EXECUTOR,
    StratumModuleConstants.ASYNC_TASK_EXECUTOR
  })
  @Fallback
  @ConditionalOnThreading(Threading.PLATFORM)
  @ConditionalOnBooleanProperty(StratumModuleConstants.PROPAGATE_TASK_CONTEXT)
  @ConditionalOnMissingBean(
      value = {TaskExecutor.class, AsyncConfigurer.class},
      name = {
        StratumModuleConstants.APPLICATION_TASK_EXECUTOR,
        StratumModuleConstants.ASYNC_TASK_EXECUTOR
      })
  public ThreadPoolTaskExecutor applicationTaskExecutor(ThreadPoolTaskExecutorBuilder builder) {
    return builder.build();
  }

  /**
   * Gives {@code @Async} methods the virtual-thread executor Spring Boot would have made, under the
   * same conditions as {@link #applicationTaskExecutor}, when the service runs on virtual threads.
   *
   * @param builder Spring Boot's builder for virtual-thread executors
   * @return the executor
   */
  @Bean({
    StratumModuleConstants.APPLICATION_TASK_EXECUTOR,
    StratumModuleConstants.ASYNC_TASK_EXECUTOR
  })
  @Fallback
  @ConditionalOnThreading(Threading.VIRTUAL)
  @ConditionalOnBooleanProperty(StratumModuleConstants.PROPAGATE_TASK_CONTEXT)
  @ConditionalOnMissingBean(
      value = {TaskExecutor.class, AsyncConfigurer.class},
      name = {
        StratumModuleConstants.APPLICATION_TASK_EXECUTOR,
        StratumModuleConstants.ASYNC_TASK_EXECUTOR
      })
  public SimpleAsyncTaskExecutor applicationTaskExecutorVirtualThreads(
      SimpleAsyncTaskExecutorBuilder builder) {
    return builder.build();
  }
}
