package com.exepex.stratum.logging.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    classes = {
      OrdersApplication.class,
      ServiceExecutorServiceBeanIntegrationTest.ServicePools.class,
      ServiceExecutorServiceBeanIntegrationTest.ReceiptSender.class
    },
    properties = {"spring.application.name=orders", "stratum.logging.enabled=true"})
// Closing the context resets logging, so later tests in this JVM start from Spring Boot's own.
@DirtiesContext
@DisplayName(
    "A service that declares its own ExecutorService bean keeps its MDC fields on every thread")
class ServiceExecutorServiceBeanIntegrationTest {

  /** The MDC field the request puts, as a service would. */
  private static final String CUSTOMER_ID = "customerId";

  /** The customer whose request the test runs. */
  private static final String CUSTOMER = "CUST-7";

  @Autowired private ExecutorService invoiceRenderingPool;

  @Autowired private ReceiptSender receiptSender;

  /** Clears the logging context, so no test sees fields left by another. */
  @AfterEach
  void clearLoggingContext() {
    MDC.clear();
  }

  @Test
  @DisplayName("a task on the service's own ExecutorService bean sees the request's field")
  void serviceExecutorServiceBeanCarriesField() {
    MDC.put(CUSTOMER_ID, CUSTOMER);

    var seenInTask =
        CompletableFuture.supplyAsync(() -> MDC.get(CUSTOMER_ID), invoiceRenderingPool).join();

    assertThat(seenInTask).isEqualTo(CUSTOMER);
  }

  @Test
  @DisplayName("an @Async method still sees the request's field when the service has its own pool")
  void asyncMethodCarriesFieldNextToServicePool() {
    MDC.put(CUSTOMER_ID, CUSTOMER);

    assertThat(receiptSender.customerIdSeenInAsyncMethod().join()).isEqualTo(CUSTOMER);
  }

  /** The pools the service declares itself. */
  @Configuration(proxyBeanMethods = false)
  static class ServicePools {

    /**
     * Returns a pool the service declares as an {@code ExecutorService}, as many services do.
     *
     * @return the pool
     */
    @Bean
    ExecutorService invoiceRenderingPool() {
      return Executors.newFixedThreadPool(2);
    }
  }

  /** A service bean with an {@code @Async} method. */
  @EnableAsync
  static class ReceiptSender {

    /**
     * Returns the customer ID the MDC holds on the thread Spring runs this method on.
     *
     * @return the ID, or a completed {@code null} if there was none
     */
    @Async
    public CompletableFuture<@Nullable String> customerIdSeenInAsyncMethod() {
      return CompletableFuture.completedFuture(MDC.get(CUSTOMER_ID));
    }
  }
}
