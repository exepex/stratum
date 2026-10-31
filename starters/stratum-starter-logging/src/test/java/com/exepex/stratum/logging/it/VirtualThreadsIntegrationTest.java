package com.exepex.stratum.logging.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    classes = {
      OrdersApplication.class,
      VirtualThreadsIntegrationTest.ServicePools.class,
      VirtualThreadsIntegrationTest.ReceiptSender.class
    },
    properties = {
      "spring.application.name=orders",
      "stratum.logging.enabled=true",
      "spring.threads.virtual.enabled=true"
    })
// Closing the context resets logging, so later tests in this JVM start from Spring Boot's own.
@DirtiesContext
@DisplayName("A service that turns on virtual threads keeps its MDC fields on every virtual thread")
class VirtualThreadsIntegrationTest {

  /** The MDC field the request puts, as a service would. */
  private static final String CUSTOMER_ID = "customerId";

  /** The customer whose request the test runs. */
  private static final String CUSTOMER = "CUST-7";

  /** How long a task may take before the test fails. */
  private static final long TASK_TIMEOUT_SECONDS = 10;

  /** The request's own view, which every task must match. */
  private static final SeenInTask REQUEST_FIELD_ON_VIRTUAL_THREAD = new SeenInTask(CUSTOMER, true);

  /**
   * What a task saw on the thread it ran on.
   *
   * @param customerId the customer ID in the MDC, or {@code null} if there was none
   * @param virtualThread whether the thread was virtual
   */
  record SeenInTask(@Nullable String customerId, boolean virtualThread) {

    /**
     * Returns what the current thread holds.
     *
     * @return the customer ID and whether the thread is virtual
     */
    static SeenInTask onCurrentThread() {
      return new SeenInTask(MDC.get(CUSTOMER_ID), Thread.currentThread().isVirtual());
    }
  }

  @Autowired private ReceiptSender receiptSender;

  @Autowired private ExecutorService invoiceRenderingPool;

  @Autowired
  @Qualifier("applicationTaskExecutor")
  private AsyncTaskExecutor applicationTaskExecutor;

  /** Puts the request's field, as a service does when a request starts. */
  @BeforeEach
  void putRequestField() {
    MDC.put(CUSTOMER_ID, CUSTOMER);
  }

  /** Clears the logging context, so no test sees fields left by another. */
  @AfterEach
  void clearLoggingContext() {
    MDC.clear();
  }

  @Test
  @DisplayName("an @Async method runs on a virtual thread and sees the request's field")
  void asyncMethodCarriesField() {
    assertThat(
            receiptSender
                .seenInAsyncMethod()
                .orTimeout(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .join())
        .isEqualTo(REQUEST_FIELD_ON_VIRTUAL_THREAD);
  }

  @Test
  @DisplayName("a task on Spring Boot's task executor runs on a virtual thread and sees the field")
  void springTaskExecutorCarriesField() {
    assertThat(
            applicationTaskExecutor
                .submitCompletable(SeenInTask::onCurrentThread)
                .orTimeout(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .join())
        .isEqualTo(REQUEST_FIELD_ON_VIRTUAL_THREAD);
  }

  @Test
  @DisplayName("a task on the service's own virtual-thread ExecutorService bean sees the field")
  void serviceVirtualThreadPoolCarriesField() {
    assertThat(
            CompletableFuture.supplyAsync(SeenInTask::onCurrentThread, invoiceRenderingPool)
                .orTimeout(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .join())
        .isEqualTo(REQUEST_FIELD_ON_VIRTUAL_THREAD);
  }

  /** The pools the service declares itself. */
  @Configuration(proxyBeanMethods = false)
  static class ServicePools {

    /**
     * Returns a virtual-thread executor the service declares as an {@code ExecutorService}.
     *
     * @return the executor
     */
    @Bean
    ExecutorService invoiceRenderingPool() {
      return Executors.newVirtualThreadPerTaskExecutor();
    }
  }

  /** A service bean with an {@code @Async} method. */
  @EnableAsync
  static class ReceiptSender {

    /**
     * Returns what the thread Spring runs this method on holds.
     *
     * @return the customer ID and whether the thread is virtual
     */
    @Async
    public CompletableFuture<SeenInTask> seenInAsyncMethod() {
      return CompletableFuture.completedFuture(SeenInTask.onCurrentThread());
    }
  }
}
