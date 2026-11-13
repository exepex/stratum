package com.exepex.stratum.web;

import static com.exepex.stratum.web.testsupport.DatabaseFailureTestData.duplicateKey;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.Severity;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.test.OrderErrorCode;
import com.exepex.stratum.web.testsupport.DatabaseFailureTestData;
import com.exepex.stratum.web.testsupport.WebTestData;
import java.sql.SQLTimeoutException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

@DisplayName("ErrorResponseFactory answers a database failure with its own status in strict mode")
@ExtendWith(OutputCaptureExtension.class)
class ErrorResponseFactoryDatabaseTest {

  /** A statement the database cancelled, as Spring reports it, with the driver's message. */
  private static final QueryTimeoutException TIMEOUT =
      new QueryTimeoutException(
          DatabaseFailureTestData.DUPLICATE_KEY_MESSAGE,
          DatabaseFailureTestData.sqlFailure("57014"));

  /** The factory in strict mode, with no status mapped. */
  private final ErrorResponseFactory strictFactory =
      new ErrorResponseFactory(WebTestData.defaults().build(), false);

  /** Returns the body the factory gives the failure, which must be one. */
  private ProblemDetail answer(ErrorResponseFactory factory, Throwable failure) {
    return factory.forFailureWithErrorCode(failure).orElseThrow();
  }

  /** Checks the body: the status, the code and its fixed text, and nothing from the driver. */
  private void assertBody(ProblemDetail body, HttpStatus status, WebErrorCode code) {
    assertThat(body.getStatus()).isEqualTo(status.value());
    assertThat(body.getDetail()).isEqualTo(code.getDescription());
    assertThat(body.getProperties())
        .containsEntry(WebConstants.CODE, code.getCode())
        .containsEntry(WebConstants.SEVERITY, Severity.ERROR)
        .doesNotContainKey(WebConstants.DETAILS);
    assertThat(strictFactory.membersOf(body).toString())
        .doesNotContain(DatabaseFailureTestData.PRIVATE_PARTS);
  }

  /** Checks that the code was logged once, at the level, with the exception's stack trace. */
  private void assertLoggedOnceWithStack(CapturedOutput output, String level, WebErrorCode code) {
    var lines = output.getOut().lines().filter(line -> line.contains(code.getCode())).toList();
    assertThat(lines).hasSize(1);
    assertThat(lines.getFirst()).contains(level);
    assertThat(output.getOut()).contains("\tat ");
  }

  @Nested
  @DisplayName("a busy database")
  class Busy {

    @Test
    @DisplayName("gets 503 and SX-WEB-1010, logged once at ERROR with its stack trace")
    void defaultStatus(CapturedOutput output) {
      assertBody(
          answer(strictFactory, TIMEOUT),
          HttpStatus.SERVICE_UNAVAILABLE,
          WebErrorCode.DATABASE_BUSY);
      assertLoggedOnceWithStack(output, "ERROR", WebErrorCode.DATABASE_BUSY);
    }

    @Test
    @DisplayName("gets the status the service maps to SX-WEB-1010 instead")
    void mappedStatus() {
      var factory =
          new ErrorResponseFactory(
              WebTestData.withErrorStatuses(
                  Map.of(WebErrorCode.DATABASE_BUSY.getCode(), HttpStatus.GATEWAY_TIMEOUT.value())),
              false);

      assertBody(answer(factory, TIMEOUT), HttpStatus.GATEWAY_TIMEOUT, WebErrorCode.DATABASE_BUSY);
    }
  }

  @Nested
  @DisplayName("a data conflict")
  class Conflict {

    @Test
    @DisplayName("gets 409 and SX-WEB-1011, logged once at WARN with its stack trace")
    void defaultStatus(CapturedOutput output) {
      assertBody(
          answer(strictFactory, duplicateKey()), HttpStatus.CONFLICT, WebErrorCode.DATA_CONFLICT);
      assertLoggedOnceWithStack(output, "WARN", WebErrorCode.DATA_CONFLICT);
    }

    @Test
    @DisplayName("gets the status the service maps to SX-WEB-1011 instead, still logged at WARN")
    void mappedStatus(CapturedOutput output) {
      var factory =
          new ErrorResponseFactory(
              WebTestData.withErrorStatuses(
                  Map.of(
                      WebErrorCode.DATA_CONFLICT.getCode(),
                      HttpStatus.UNPROCESSABLE_CONTENT.value())),
              false);

      assertBody(
          answer(factory, duplicateKey()),
          HttpStatus.UNPROCESSABLE_CONTENT,
          WebErrorCode.DATA_CONFLICT);
      assertLoggedOnceWithStack(output, "WARN", WebErrorCode.DATA_CONFLICT);
    }
  }

  @Test
  @DisplayName("a code the failure carries wins over the database failure beneath it")
  void carriedCodeWins() {
    var failure =
        new StratumException(OrderErrorCode.STORE_DOWN, "store", new SQLTimeoutException());

    assertThat(answer(strictFactory, failure).getProperties())
        .containsEntry(WebConstants.CODE, OrderErrorCode.STORE_DOWN.getCode());
  }

  @Test
  @DisplayName("in backward-compatible mode, a database failure is left to Spring Boot")
  void compatibleModeLeavesIt() {
    var compatibleFactory = new ErrorResponseFactory(WebTestData.defaults().build(), true);

    assertThat(compatibleFactory.forFailureWithErrorCode(TIMEOUT)).isEmpty();
    assertThat(compatibleFactory.forFailureWithErrorCode(duplicateKey())).isEmpty();
  }
}
