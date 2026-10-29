package com.exepex.stratum.logging.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.LoggingErrorCode;
import com.exepex.stratum.logging.workflow.Workflow;
import com.exepex.stratum.logging.workflow.Workflows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;

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
  @DisplayName("a logged StratumException's code is its own field")
  void writesErrorCode(CapturedOutput output) {
    var failure = new StratumException(LoggingErrorCode.APPLICATION_NAME_MISSING);

    log.error("startup check", failure);

    assertThat(output).contains("\"error.code\":\"SX-LOGGING-1001\"");
  }

  /** A service bean with a workflow method. */
  @Slf4j
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
  }
}
