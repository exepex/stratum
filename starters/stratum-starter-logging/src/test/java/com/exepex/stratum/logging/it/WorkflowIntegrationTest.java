package com.exepex.stratum.logging.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.LoggingErrorCode;
import com.exepex.stratum.logging.workflow.ReactiveWorkflows;
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
import reactor.core.publisher.Flux;
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

  /** The order number the workflows run for. */
  private static final String ORDER_NUMBER = "ORD-1042";

  /** The checkout workflow's name. */
  private static final String CHECKOUT = "ORDER_CHECKOUT";

  /** The message logged when the receipt is sent. */
  private static final String RECEIPT_SENT = "receipt sent";

  /** The message logged when the payment is taken. */
  private static final String PAYMENT_TAKEN = "payment taken";

  /** The message logged when the payment is reserved, in a {@code map} operator. */
  private static final String PAYMENT_RESERVED_IN_MAP = "payment reserved in map";

  /** The message logged when a parcel is shipped, in a {@code map} operator. */
  private static final String PARCEL_SHIPPED_IN_MAP = "parcel shipped in map";

  @Autowired private CheckoutService checkoutService;

  @Autowired private Workflows workflows;

  @Autowired private ReactiveWorkflows reactiveWorkflows;

  @Test
  @DisplayName("a line written in an @Workflow method carries the workflow and its root")
  void annotatedMethodWritesWorkflowFields(CapturedOutput output) {
    checkoutService.checkOut();

    assertThat(output)
        .contains("\"message\":\"order checked out\"")
        .contains("\"workflow\":\"ORDER_CHECKOUT\"")
        .contains("\"rootWorkflow\":\"ORDER_CHECKOUT\"")
        .doesNotContain("\"root_workflow\"");
  }

  @Test
  @DisplayName("a line written inside Workflows.run carries the workflow's ID")
  void workflowsWriteWorkflowId(CapturedOutput output) {
    workflows.run("ORDER_REFUND", ORDER_NUMBER, checkoutService::refund);

    assertThat(output)
        .contains("\"workflow\":\"ORDER_REFUND\"")
        .contains("\"rootWorkflow\":\"ORDER_REFUND\"")
        .contains("\"workflowId\":\"ORD-1042\"")
        .doesNotContain("\"workflow_id\"");
  }

  @Test
  @DisplayName("a line written in an @Async method called inside a workflow carries its fields")
  void asyncMethodWritesWorkflowFields(CapturedOutput output) {
    workflows.call(CHECKOUT, ORDER_NUMBER, checkoutService::sendReceipt).join();

    assertThat(lineWith(output, RECEIPT_SENT))
        .contains("\"workflow\":\"ORDER_CHECKOUT\"")
        .contains("\"workflowId\":\"ORD-1042\"")
        .doesNotContain("\"process.thread.name\":\"main\"");
  }

  @Test
  @DisplayName("a line written while an @Workflow method's Mono runs carries the workflow")
  void annotatedMonoMethodWritesWorkflowFields(CapturedOutput output) {
    checkoutService.takePayment().block();

    assertThat(lineWith(output, PAYMENT_TAKEN)).contains("\"workflow\":\"PAYMENT\"");
  }

  @Test
  @DisplayName(
      "a line written in map, after a Mono's work moved to another thread, carries the workflow")
  void annotatedMonoMethodWritesWorkflowFieldsInMap(CapturedOutput output) {
    checkoutService.reservePaymentAfterThreadChange().block();

    assertThat(lineWith(output, PAYMENT_RESERVED_IN_MAP))
        .contains("\"workflow\":\"PAYMENT_RESERVATION\"")
        .doesNotContain("\"process.thread.name\":\"main\"");
  }

  @Test
  @DisplayName(
      "a line written in map, after a Flux's work moved to another thread, carries the workflow ID")
  void reactiveWorkflowFluxWritesWorkflowFieldsInMap(CapturedOutput output) {
    reactiveWorkflows
        .flux(
            "ORDER_SHIPPING",
            "ORD-2044",
            Flux.just("parcel-1")
                .publishOn(Schedulers.parallel())
                .map(checkoutService::logParcelShippedInMap))
        .blockLast();

    assertThat(lineWith(output, PARCEL_SHIPPED_IN_MAP))
        .contains("\"workflow\":\"ORDER_SHIPPING\"")
        .contains("\"workflowId\":\"ORD-2044\"");
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
    @Workflow(CHECKOUT)
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
      log.info(RECEIPT_SENT);
      return CompletableFuture.completedFuture(null);
    }

    /**
     * Takes the payment, as the {@code PAYMENT} workflow, on another thread.
     *
     * @return the payment, taken when subscribed
     */
    @Workflow("PAYMENT")
    public Mono<String> takePayment() {
      return Mono.just(ORDER_NUMBER)
          .publishOn(Schedulers.boundedElastic())
          .handle(this::logPayment);
    }

    /**
     * Reserves the payment, as the {@code PAYMENT_RESERVATION} workflow, logging in {@code map} on
     * another thread.
     *
     * @return the reservation, made when subscribed
     */
    @Workflow("PAYMENT_RESERVATION")
    public Mono<String> reservePaymentAfterThreadChange() {
      return Mono.just("ORD-3055")
          .publishOn(Schedulers.boundedElastic())
          .map(this::logPaymentReservedInMap);
    }

    /** Logs the reservation from a {@code map} operator and passes the order number on. */
    private String logPaymentReservedInMap(String orderNumber) {
      log.info(PAYMENT_RESERVED_IN_MAP);
      return orderNumber;
    }

    /**
     * Logs a shipped parcel from a {@code map} operator and passes it on.
     *
     * @param parcel the parcel
     * @return the same parcel
     */
    public String logParcelShippedInMap(String parcel) {
      log.info(PARCEL_SHIPPED_IN_MAP);
      return parcel;
    }

    /** Logs the payment, where Reactor gives the workflow fields to the logging context. */
    private void logPayment(String orderNumber, SynchronousSink<String> sink) {
      log.info(PAYMENT_TAKEN);
      sink.next(orderNumber);
    }
  }
}
