package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.test.OrderErrorCode;
import com.exepex.stratum.web.testsupport.RefusedOrderException;
import com.exepex.stratum.web.testsupport.WebTestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

@DisplayName(
    "ErrorResponseFactory answers an UnloggedFailure without a log line while it is mapped")
@ExtendWith(OutputCaptureExtension.class)
class ErrorResponseFactoryUnloggedTest {

  /** The code of the refused failure. */
  private static final String CODE = OrderErrorCode.STORE_DOWN.getCode();

  /** Returns the factory with the code mapped to the statuses given, or not mapped when empty. */
  private ErrorResponseFactory factory(Map<String, Integer> statuses) {
    return new ErrorResponseFactory(WebTestData.withErrorStatuses(statuses), false);
  }

  /** Returns the body the factory gives the failure, which must be one. */
  private ProblemDetail answer(ErrorResponseFactory factory, Throwable failure) {
    return factory.forFailureWithErrorCode(failure).orElseThrow();
  }

  /** Returns the factory's log lines that name the code, without the lines of a stack trace. */
  private List<String> linesNamingCode(CapturedOutput output) {
    return output
        .getOut()
        .lines()
        .filter(line -> line.contains(CODE) && line.contains(ErrorResponseFactory.class.getName()))
        .toList();
  }

  @Nested
  @DisplayName("with its code mapped")
  class Mapped {

    /** Statuses a team maps the refusal to: the default 503 and a team's 429. */
    private static final List<Integer> STATUSES = List.of(503, 429);

    @ParameterizedTest
    @FieldSource("STATUSES")
    @DisplayName("it gets the mapped status and its code, and writes no log line")
    void answersWithoutLogLine(int status, CapturedOutput output) {
      var body =
          answer(
              factory(Map.of(CODE, status)), new RefusedOrderException(OrderErrorCode.STORE_DOWN));

      assertThat(body.getStatus()).isEqualTo(status);
      assertThat(body.getProperties()).containsEntry(WebConstants.CODE, CODE);
      assertThat(linesNamingCode(output)).isEmpty();
    }
  }

  @Nested
  @DisplayName("with its code not mapped")
  class Unmapped {

    @Test
    @DisplayName("it gets 500 and one ERROR line with the note that the code has no status")
    void logsUnmappedCode(CapturedOutput output) {
      var body = answer(factory(Map.of()), new RefusedOrderException(OrderErrorCode.STORE_DOWN));

      assertThat(body.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
      assertThat(linesNamingCode(output))
          .singleElement()
          .satisfies(line -> assertThat(line).contains("ERROR", "has no status in"));
    }
  }

  @Nested
  @DisplayName("a failure that is not an UnloggedFailure")
  class OtherFailures {

    @Test
    @DisplayName("a mapped 5xx StratumException is still logged once at ERROR with its stack trace")
    void logsServerError(CapturedOutput output) {
      answer(
          factory(Map.of(CODE, HttpStatus.SERVICE_UNAVAILABLE.value())),
          new StratumException(OrderErrorCode.STORE_DOWN));

      assertThat(linesNamingCode(output))
          .singleElement()
          .satisfies(line -> assertThat(line).contains("ERROR"));
      assertThat(output.getOut()).contains("\tat ");
    }

    @Test
    @DisplayName("a mapped 4xx StratumException is still logged once at WARN")
    void logsClientError(CapturedOutput output) {
      answer(
          factory(Map.of(CODE, HttpStatus.CONFLICT.value())),
          new StratumException(OrderErrorCode.STORE_DOWN));

      assertThat(linesNamingCode(output))
          .singleElement()
          .satisfies(line -> assertThat(line).contains("WARN"));
    }
  }
}
