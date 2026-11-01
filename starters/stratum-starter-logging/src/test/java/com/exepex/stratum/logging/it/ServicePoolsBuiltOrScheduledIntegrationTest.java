package com.exepex.stratum.logging.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    classes = {
      OrdersApplication.class,
      ServicePoolsBuiltOrScheduledIntegrationTest.ServicePools.class
    },
    properties = {"spring.application.name=orders", "stratum.logging.enabled=true"})
// Closing the context resets logging, so later tests in this JVM start from Spring Boot's own.
@DirtiesContext
@DisplayName("A service's Spring pool built with Spring Boot's builder, and its scheduled pool")
class ServicePoolsBuiltOrScheduledIntegrationTest {

  /** The MDC field the request puts, as a service would. */
  private static final String CUSTOMER_ID = "customerId";

  /** The customer whose request the test runs. */
  private static final String CUSTOMER = "CUST-7";

  /** How long a test waits for a task, so a broken pool fails the test instead of hanging. */
  private static final long WAIT_SECONDS = 5;

  @Autowired private ThreadPoolTaskExecutor reportPool;

  @Autowired private ScheduledExecutorService reminderScheduler;

  @Autowired private CountingTaskDecorator teamDecorator;

  /** Clears the logging context, so no test sees fields left by another. */
  @AfterEach
  void clearLoggingContext() {
    MDC.clear();
  }

  @Test
  @DisplayName("a pool built with the builder carries the field and also runs the team's decorator")
  void builderPoolCarriesFieldAndRunsTeamDecorator() throws Exception {
    MDC.put(CUSTOMER_ID, CUSTOMER);
    var runsBefore = teamDecorator.decoratedTasks.get();

    var seenInTask =
        reportPool.submit(() -> MDC.get(CUSTOMER_ID)).get(WAIT_SECONDS, TimeUnit.SECONDS);

    assertThat(seenInTask).isEqualTo(CUSTOMER);
    assertThat(teamDecorator.decoratedTasks.get()).isGreaterThan(runsBefore);
  }

  @Test
  @DisplayName("a scheduled pool carries the field into a task that runs once")
  void oneShotTaskCarriesField() throws Exception {
    MDC.put(CUSTOMER_ID, CUSTOMER);

    var seenInTask =
        reminderScheduler
            .schedule(() -> MDC.get(CUSTOMER_ID), 1, TimeUnit.MILLISECONDS)
            .get(WAIT_SECONDS, TimeUnit.SECONDS);

    assertThat(seenInTask).isEqualTo(CUSTOMER);
  }

  @Test
  @DisplayName(
      "a scheduled pool runs a repeating task without the field of the code that scheduled it")
  void repeatingTaskRunsWithoutField() throws Exception {
    MDC.put(CUSTOMER_ID, CUSTOMER);
    var firstRun = new CompletableFuture<String>();

    var repeating =
        reminderScheduler.scheduleAtFixedRate(
            () -> firstRun.complete(String.valueOf(MDC.get(CUSTOMER_ID))),
            1,
            1,
            TimeUnit.MILLISECONDS);
    try {
      assertThat(firstRun.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo("null");
    } finally {
      repeating.cancel(true);
    }
  }

  /** A decorator the team declares as a bean, which counts the tasks it decorates. */
  static class CountingTaskDecorator implements TaskDecorator {

    /** How many tasks this decorator has wrapped. */
    private final AtomicInteger decoratedTasks = new AtomicInteger();

    /**
     * Counts the task and returns it unchanged.
     *
     * @param task the task
     * @return the same task
     */
    @Override
    public Runnable decorate(Runnable task) {
      decoratedTasks.incrementAndGet();
      return task;
    }
  }

  /** The pools and the decorator the service declares itself. */
  @Configuration(proxyBeanMethods = false)
  static class ServicePools {

    /**
     * Returns the team's own decorator.
     *
     * @return the decorator
     */
    @Bean
    CountingTaskDecorator teamDecorator() {
      return new CountingTaskDecorator();
    }

    /**
     * Returns a Spring pool built with Spring Boot's builder, as the docs advise.
     *
     * @param builder Spring Boot's builder
     * @return the pool
     */
    @Bean
    ThreadPoolTaskExecutor reportPool(ThreadPoolTaskExecutorBuilder builder) {
      return builder.threadNamePrefix("report-").build();
    }

    /**
     * Returns a pool the service declares as a {@code ScheduledExecutorService}.
     *
     * @return the pool
     */
    @Bean
    ScheduledExecutorService reminderScheduler() {
      return Executors.newSingleThreadScheduledExecutor();
    }
  }
}
