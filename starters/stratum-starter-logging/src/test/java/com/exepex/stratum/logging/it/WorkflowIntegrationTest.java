package com.exepex.stratum.logging.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.LoggingErrorCode;
import com.exepex.stratum.logging.workflow.Workflow;
import com.exepex.stratum.logging.workflow.Workflows;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.test.annotation.DirtiesContext;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;
import reactor.core.scheduler.Schedulers;

@SpringBootTest(
    classes = {OrdersApplication.class, WorkflowIntegrationTest.CheckoutService.class},
    properties = {"spring.application.name=orders", "stratum.logging.enabled=true"})
// Closing the context resets logging, so later tests in this JVM start from Spring Boot's own.
@DirtiesContext
@Slf4j
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("A service with AspectJ gets workflow fields and error codes on its log lines")
class WorkflowIntegrationTest {

  @Autowired private CheckoutService checkoutService;

  @Autowired private Workflows workflows;

  @Test
  @DisplayName("a line written in an @Workflow method carries the workflow and its root")
  void annotatedMethodWritesWorkflowFields(CapturedOutput output) {
    checkoutService.checkOut();

    assertThat(output)
        .contains("\"message\":\"order checked out\"")
        .contains("\"workflow\":\"ORDER_CHECKOUT\"")
        .contains("\"root_workflow\":\"ORDER_CHECKOUT\"");
  }

  @Test
  @DisplayName("a line written inside Workflows.run carries the workflow's ID")
  void workflowsWriteWorkflowId(CapturedOutput output) {
    workflows.run("ORDER_REFUND", "ORD-1042", checkoutService::refund);

    assertThat(output)
        .contains("\"workflow\":\"ORDER_REFUND\"")
        .contains("\"workflow_id\":\"ORD-1042\"");
  }

  @Test
  @DisplayName("a line written in an @Async method called inside a workflow carries its fields")
  void asyncMethodWritesWorkflowFields(CapturedOutput output) {
    workflows.call("ORDER_CHECKOUT", "ORD-1042", checkoutService::sendReceipt).join();

    assertThat(lineWith(output, "receipt sent"))
        .contains("\"workflow\":\"ORDER_CHECKOUT\"")
        .contains("\"workflow_id\":\"ORD-1042\"")
        .doesNotContain("\"process.thread.name\":\"main\"");
  }

  @Test
  @DisplayName("a line written while an @Workflow method's Mono runs carries the workflow")
  void annotatedMonoMethodWritesWorkflowFields(CapturedOutput output) {
    checkoutService.takePayment().block();

    assertThat(lineWith(output, "payment taken")).contains("\"workflow\":\"PAYMENT\"");
  }

  @Test
  @DisplayName("a logged StratumException's code is its own field")
  void writesErrorCode(CapturedOutput output) {
    var failure = new StratumException(LoggingErrorCode.APPLICATION_NAME_MISSING);

    log.error("startup check", failure);

    assertThat(output).contains("\"error.code\":\"SX-LOGGING-1001\"");
  }

  /** Returns the captured log line with the message. */
  private String lineWith(CapturedOutput output, String message) {
    return output
        .getOut()
        .lines()
        .filter(line -> line.contains("\"message\":\"" + message + "\""))
        .findFirst()
        .orElseThrow();
  }

  /** A service bean with workflow methods, and methods that run on Spring's task executor. */
  @Slf4j
  @EnableAsync
  static class CheckoutService {

    /** Checks out an order, as the {@code ORDER_CHECKOUT} workflow. */
    @Workflow("ORDER_CHECKOUT")
    public void checkOut() {
      log.info("order checked out");
    }

    /** Refunds an order. */
    public void refund() {
      log.info("order refunded");
    }

    /**
     * Sends the receipt on Spring's task executor.
     *
     * @return when the receipt is sent
     */
    @Async
    public CompletableFuture<Void> sendReceipt() {
      log.info("receipt sent");
      return CompletableFuture.completedFuture(null);
    }

    /**
     * Takes the payment, as the {@code PAYMENT} workflow, on another thread.
     *
     * @return the payment, taken when subscribed
     */
    @Workflow("PAYMENT")
    public Mono<String> takePayment() {
      return Mono.just("ORD-1042").publishOn(Schedulers.boundedElastic()).handle(this::logPayment);
    }

    /** Logs the payment, where Reactor gives the workflow fields to the logging context. */
    private void logPayment(String orderNumber, SynchronousSink<String> sink) {
      log.info("payment taken");
      sink.next(orderNumber);
    }
  }
}
