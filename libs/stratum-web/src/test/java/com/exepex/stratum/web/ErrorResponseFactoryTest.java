package com.exepex.stratum.web;

import static com.exepex.stratum.web.testsupport.WebTestData.CORRELATION_ID;
import static com.exepex.stratum.web.testsupport.WebTestData.TRACE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.ErrorDetail;
import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.core.error.Severity;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.test.ExistingOrderException;
import com.exepex.stratum.test.OrderErrorCode;
import com.exepex.stratum.web.testsupport.WebTestData;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BindException;
import org.springframework.validation.MapBindingResult;

@DisplayName("ErrorResponseFactory makes the same error body for every failure")
class ErrorResponseFactoryTest {

  /** What is specific to a duplicate order. */
  private static final String ORDER_DETAIL = "order 42";

  /** The code of a duplicate order, mapped to 409. */
  private static final String DUPLICATE_ORDER_CODE = OrderErrorCode.DUPLICATE_ORDER.getCode();

  /** The factory, with ORD-0001 mapped to 409 and ORD-0002 to 503. */
  private final ErrorResponseFactory factory =
      new ErrorResponseFactory(
          WebTestData.withErrorStatuses(
              Map.of(
                  OrderErrorCode.DUPLICATE_ORDER.getCode(), HttpStatus.CONFLICT.value(),
                  OrderErrorCode.STORE_DOWN.getCode(), HttpStatus.SERVICE_UNAVAILABLE.value())));

  @Nested
  @DisplayName("for a StratumException")
  @ExtendWith(OutputCaptureExtension.class)
  class ForStratumException {

    @Test
    @DisplayName("answers with the mapped status, code, severity and message, and logs a WARN")
    void mappedCodeGetsItsStatus(CapturedOutput output) {
      var body =
          factory
              .forFailureWithErrorCode(
                  new StratumException(OrderErrorCode.DUPLICATE_ORDER, ORDER_DETAIL))
              .orElseThrow();

      assertThat(body.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
      assertThat(body.getDetail()).isEqualTo("Order already exists: order 42");
      assertThat(body.getProperties())
          .containsEntry(WebConstants.CODE, DUPLICATE_ORDER_CODE)
          .containsEntry(WebConstants.SEVERITY, Severity.ERROR)
          .doesNotContainKey(WebConstants.DETAILS);
      assertThat(output).contains("WARN", "ORD-0001: Order already exists: order 42");
    }

    @Test
    @DisplayName("logs a mapped 5xx at ERROR with its stack trace")
    void mappedServerErrorLogsStackTrace(CapturedOutput output) {
      var body = answer(new StratumException(OrderErrorCode.STORE_DOWN));

      assertThat(body.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
      assertThat(output).contains("ERROR", "ORD-0002", "at com.exepex.stratum.web.");
    }

    @Test
    @DisplayName("answers an unmapped code with 500 and logs which setting is missing")
    void unmappedCodeGets500(CapturedOutput output) {
      var body = answer(new StratumException(OrderErrorCode.UNMAPPED));

      assertThat(body.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
      assertThat(body.getProperties()).containsEntry(WebConstants.CODE, "ORD-0003");
      assertThat(output).contains("ERROR", "ORD-0003", WebConstants.ERROR_STATUSES);
    }
  }

  @Nested
  @DisplayName("for an exception that implements FailureWithErrorCode")
  @ExtendWith(OutputCaptureExtension.class)
  class ForFailureWithErrorCode {

    @Test
    @DisplayName("answers with the same body as a StratumException with that code and detail")
    void sameBodyAsStratumException(CapturedOutput output) {
      var body = answer(new ExistingOrderException(OrderErrorCode.DUPLICATE_ORDER, ORDER_DETAIL));

      assertThat(factory.membersOf(body))
          .isEqualTo(
              factory.membersOf(
                  answer(new StratumException(OrderErrorCode.DUPLICATE_ORDER, ORDER_DETAIL))));
      assertThat(output).contains("WARN", "ORD-0001: Order already exists: order 42");
    }

    @Test
    @DisplayName("answers one wrapped as the cause of another exception in the same way")
    void wrappedFailureWithErrorCode() {
      var wrapped =
          new IllegalStateException(
              new StratumException(OrderErrorCode.DUPLICATE_ORDER, ORDER_DETAIL));

      assertThat(factory.membersOf(answer(wrapped)))
          .isEqualTo(
              factory.membersOf(
                  answer(new StratumException(OrderErrorCode.DUPLICATE_ORDER, ORDER_DETAIL))));
    }

    @Test
    @Timeout(5)
    @DisplayName("stops at a chain of causes that loops back on itself")
    void causesThatLoop() {
      var first = new IllegalStateException("first");
      var second = new IllegalArgumentException("second", first);
      first.initCause(second);

      assertThat(factory.forFailureWithErrorCode(first)).isEmpty();
    }

    @Test
    @DisplayName("answers a warning code, which is never thrown, with 500 and SX-WEB-1005")
    void warningCodeGets500(CapturedOutput output) {
      var body = answer(new ExistingOrderException(OrderErrorCode.SHIPS_LATE, "3 days"));

      assertThat(body.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
      assertThat(body.getProperties())
          .containsEntry(WebConstants.CODE, WebErrorCode.INTERNAL_ERROR.getCode())
          .containsEntry(WebConstants.SEVERITY, Severity.ERROR);
      assertThat(output).contains("ERROR", "ORD-0004", "is a warning, which is never thrown");
    }

    @Test
    @DisplayName("leaves any other failure alone")
    void otherFailureGetsNothing() {
      assertThat(factory.forFailureWithErrorCode(new IllegalStateException("broken"))).isEmpty();
    }
  }

  @Nested
  @DisplayName("for a request that failed outside the service's handlers")
  class ForFailedRequest {

    /** Each status with the web code it gets. */
    private static final List<Arguments> STATUS_CODES =
        List.of(
            Arguments.of(400, "SX-WEB-1002"),
            Arguments.of(404, "SX-WEB-1003"),
            Arguments.of(413, "SX-WEB-1008"),
            Arguments.of(405, "SX-WEB-1004"),
            Arguments.of(499, "SX-WEB-1004"),
            Arguments.of(500, "SX-WEB-1005"));

    @ParameterizedTest(name = "{0} gets {1}")
    @FieldSource("STATUS_CODES")
    @DisplayName("picks the web code of the status")
    void statusGetsItsCode(int status, String expectedCode) {
      var body = factory.forFailedRequest(HttpStatusCode.valueOf(status), null);

      assertThat(body.getStatus()).isEqualTo(status);
      assertThat(body.getProperties()).containsEntry(WebConstants.CODE, expectedCode);
    }

    @Test
    @DisplayName("keeps the status of a StratumException thrown outside the handlers")
    void stratumExceptionKeepsTheStatus() {
      var body =
          factory.forFailedRequest(
              HttpStatus.BAD_GATEWAY, new StratumException(OrderErrorCode.DUPLICATE_ORDER));

      assertThat(body.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
      assertThat(body.getProperties()).containsEntry(WebConstants.CODE, DUPLICATE_ORDER_CODE);
    }

    @Test
    @DisplayName("keeps the status of a FailureWithErrorCode thrown outside the handlers")
    void failureWithErrorCodeKeepsTheStatus() {
      var body =
          factory.forFailedRequest(
              HttpStatus.BAD_GATEWAY,
              new ExistingOrderException(OrderErrorCode.DUPLICATE_ORDER, ORDER_DETAIL));

      assertThat(body.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
      assertThat(body.getDetail()).isEqualTo("Order already exists: order 42");
      assertThat(body.getProperties()).containsEntry(WebConstants.CODE, DUPLICATE_ORDER_CODE);
    }

    @Test
    @DisplayName("gives Spring's body for the status when only a cause has an error code")
    void codeOfCauseIsNotUsed() {
      var body =
          factory.forFailedRequest(
              HttpStatus.NOT_FOUND,
              new IllegalStateException(new StratumException(OrderErrorCode.DUPLICATE_ORDER)));

      assertThat(body.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
      assertThat(body.getProperties())
          .containsEntry(WebConstants.CODE, WebErrorCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("answers a request that ran out of time with SX-WEB-1007 and its status")
    void timedOutRequestGetsItsCode() {
      var body =
          factory.forFailedRequest(
              HttpStatus.GATEWAY_TIMEOUT,
              new RequestTimedOutException(HttpStatus.GATEWAY_TIMEOUT, Duration.ofMinutes(1)));

      assertThat(body.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT.value());
      assertThat(body.getDetail()).isEqualTo("The request ran out of time");
      assertThat(body.getProperties())
          .containsEntry(WebConstants.CODE, WebErrorCode.REQUEST_TIMED_OUT.getCode());
    }

    @Test
    @DisplayName("answers a failed validation with SX-WEB-1001 and one detail per field")
    void failedValidationListsFields() {
      var result = new MapBindingResult(new HashMap<>(), "orderForm");
      result.rejectValue("quantity", "Min", "must be at least 1");

      var body = factory.forFailedRequest(HttpStatus.BAD_REQUEST, new BindException(result));

      assertThat(body.getProperties())
          .containsEntry(WebConstants.CODE, WebErrorCode.INVALID_FIELDS.getCode())
          .containsEntry(
              WebConstants.DETAILS, List.of(new ErrorDetail("quantity", "must be at least 1")));
    }

    @Test
    @DisplayName("never shows the exception's message")
    void exceptionMessageIsNotShown() {
      var body =
          factory.forFailedRequest(
              HttpStatus.INTERNAL_SERVER_ERROR, new IllegalStateException("db password wrong"));

      assertThat(body.getDetail()).isEqualTo(WebErrorCode.INTERNAL_ERROR.getDescription());
      assertThat(factory.membersOf(body).toString()).doesNotContain("password");
    }
  }

  @Nested
  @DisplayName("adds request IDs")
  class AddRequestIds {

    @Test
    @DisplayName("adds the trace and correlation IDs a body does not have")
    void addsMissingIds() {
      var body = ProblemDetail.forStatus(HttpStatus.CONFLICT);

      factory.addRequestIds(body, TRACE_ID, CORRELATION_ID);

      assertThat(body.getProperties())
          .containsEntry(LoggingContextConstants.TRACE_ID, TRACE_ID)
          .containsEntry(LoggingContextConstants.CORRELATION_ID, CORRELATION_ID);
    }

    @Test
    @DisplayName("keeps an ID the service's handler already set")
    void keepsIdsAlreadySet() {
      var body = ProblemDetail.forStatus(HttpStatus.CONFLICT);
      body.setProperty(LoggingContextConstants.CORRELATION_ID, "set-by-service");

      factory.addRequestIds(body, TRACE_ID, CORRELATION_ID);

      assertThat(body.getProperties())
          .containsEntry(LoggingContextConstants.CORRELATION_ID, "set-by-service");
    }

    @Test
    @DisplayName("leaves out an ID that is missing or unsafe")
    void leavesOutMissingOrUnsafeIds() {
      var body = ProblemDetail.forStatus(HttpStatus.CONFLICT);

      factory.addRequestIds(body, null, "forged\nline");

      assertThat(body.getProperties()).isNull();
    }
  }

  @Test
  @DisplayName("lists a body's members in the order they are written")
  void membersAreInWrittenOrder() {
    var body = answer(new StratumException(OrderErrorCode.DUPLICATE_ORDER));

    assertThat(factory.membersOf(body))
        .containsExactly(
            Map.entry("type", "about:blank"),
            Map.entry("title", "Conflict"),
            Map.entry("status", HttpStatus.CONFLICT.value()),
            Map.entry("detail", "Order already exists"),
            Map.entry(WebConstants.CODE, DUPLICATE_ORDER_CODE),
            Map.entry(WebConstants.SEVERITY, Severity.ERROR));
  }

  /** Answers a failure that carries an error code. */
  private ProblemDetail answer(Throwable failure) {
    return factory.forFailureWithErrorCode(failure).orElseThrow();
  }
}
