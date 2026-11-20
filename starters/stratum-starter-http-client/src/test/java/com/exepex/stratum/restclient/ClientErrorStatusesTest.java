package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.web.ErrorResponseFactory;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import java.net.ConnectException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.SpringApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.ResourceAccessException;

@DisplayName("The codes of a call get their status from the web starter's map, which the team wins")
class ClientErrorStatusesTest {

  /** The key of the unavailable code's status, as a team writes it in a properties file. */
  private static final String TEAM_UNAVAILABLE_STATUS =
      HttpClientConstants.WEB_ERROR_STATUSES
          + "."
          + HttpClientErrorCode.DOWNSTREAM_UNAVAILABLE.getCode();

  /** The key of the status of an answer that is a refusal for now, as a team writes it. */
  private static final String TEAM_REFUSED_FOR_NOW_STATUS =
      HttpClientConstants.WEB_ERROR_STATUSES
          + "."
          + HttpClientErrorCode.DOWNSTREAM_REFUSED_FOR_NOW.getCode();

  @Nested
  @DisplayName("a call that got no answer")
  class NoAnswer {

    @Test
    @DisplayName(
        "a timeout answers 504, no answer and an open breaker 503, with no downstream named")
    void defaultStatuses() {
      var answers = answersFor(new MockEnvironment(), false);

      assertThat(answers.forFailureWithErrorCode(code(HttpClientErrorCode.DOWNSTREAM_TIMEOUT)))
          .get()
          .satisfies(
              body -> assertThat(body.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT.value()))
          .satisfies(
              body -> assertThat(body.getDetail()).doesNotContain(RestClientTestData.ORDERS));
      assertThat(answers.forFailureWithErrorCode(code(HttpClientErrorCode.DOWNSTREAM_UNAVAILABLE)))
          .get()
          .extracting(ProblemDetail::getStatus)
          .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
      assertThat(answers.forFailureWithErrorCode(code(HttpClientErrorCode.BREAKER_OPEN)))
          .get()
          .extracting(ProblemDetail::getStatus)
          .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    }

    @Test
    @DisplayName("a status the team maps wins, and the other defaults stay")
    void teamStatusWins() {
      var answers =
          answersFor(new MockEnvironment().withProperty(TEAM_UNAVAILABLE_STATUS, "502"), false);

      assertThat(answers.forFailureWithErrorCode(code(HttpClientErrorCode.DOWNSTREAM_UNAVAILABLE)))
          .get()
          .extracting(ProblemDetail::getStatus)
          .isEqualTo(HttpStatus.BAD_GATEWAY.value());
      assertThat(answers.forFailureWithErrorCode(code(HttpClientErrorCode.DOWNSTREAM_TIMEOUT)))
          .get()
          .extracting(ProblemDetail::getStatus)
          .isEqualTo(HttpStatus.GATEWAY_TIMEOUT.value());
    }

    @Test
    @DisplayName("with the module off, no status is given")
    void noDefaultsWhenOff() {
      var environment = new MockEnvironment().withProperty(HttpClientConstants.ENABLED, "false");

      assertThat(
              answersFor(environment, false)
                  .forFailureWithErrorCode(code(HttpClientErrorCode.BREAKER_OPEN)))
          .get()
          .extracting(ProblemDetail::getStatus)
          .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }
  }

  @Nested
  @DisplayName("an uncaught answer of the other service")
  class Answers {

    /**
     * An answer the other service may accept later and a final one, each with its code; every
     * status's code is in {@link DownstreamExceptionTest}.
     */
    private static final List<Arguments> STATUS_CODES =
        List.of(
            argumentSet(
                "503",
                HttpStatus.SERVICE_UNAVAILABLE,
                HttpClientErrorCode.DOWNSTREAM_REFUSED_FOR_NOW),
            argumentSet("404", HttpStatus.NOT_FOUND, HttpClientErrorCode.DOWNSTREAM_ERROR));

    @ParameterizedTest
    @FieldSource("STATUS_CODES")
    @DisplayName("an error answer is answered 500 with its code, never the other service's body")
    void errorAnswerIs500(HttpStatus status, HttpClientErrorCode expected) {
      var body =
          answersFor(new MockEnvironment(), false)
              .forFailureWithErrorCode(RestClientTestData.downstream(status))
              .orElseThrow();

      assertStandardBody(body, expected);
    }

    @Test
    @DisplayName("an answer that cannot be read is answered 500 with SX-HTTPC-0008")
    void unreadableAnswerIs500() {
      var body =
          answersFor(new MockEnvironment(), false)
              .forFailureWithErrorCode(RestClientTestData.unreadableAnswer())
              .orElseThrow();

      assertStandardBody(body, HttpClientErrorCode.ANSWER_UNREADABLE);
    }

    @Test
    @DisplayName("a status the team maps to SX-HTTPC-0009 wins")
    void teamStatusWins() {
      var environment = new MockEnvironment().withProperty(TEAM_REFUSED_FOR_NOW_STATUS, "503");

      assertThat(
              answersFor(environment, false)
                  .forFailureWithErrorCode(
                      RestClientTestData.downstream(HttpStatus.SERVICE_UNAVAILABLE)))
          .get()
          .extracting(ProblemDetail::getStatus)
          .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    }
  }

  @Nested
  @DisplayName("in backward-compatible mode")
  class BackwardCompatibleMode {

    @Test
    @DisplayName("an uncaught error answer gets 500 and Stratum's body, with its code")
    void errorAnswerGetsStandardBody() {
      var answers = answersFor(new MockEnvironment(), true);
      var downstream = RestClientTestData.downstream(HttpStatus.SERVICE_UNAVAILABLE);

      assertThat(answers.answersWithStandardBody(downstream, List.of())).isTrue();
      assertStandardBody(
          answers.forFailureWithErrorCode(downstream).orElseThrow(),
          HttpClientErrorCode.DOWNSTREAM_REFUSED_FOR_NOW);
    }

    @Test
    @DisplayName("an answer that cannot be read gets 500 and Stratum's body, with SX-HTTPC-0008")
    void unreadableAnswerGetsStandardBody() {
      var answers = answersFor(new MockEnvironment(), true);
      var unreadable = RestClientTestData.unreadableAnswer();

      assertThat(answers.answersWithStandardBody(unreadable, List.of())).isTrue();
      assertStandardBody(
          answers.forFailureWithErrorCode(unreadable).orElseThrow(),
          HttpClientErrorCode.ANSWER_UNREADABLE);
    }
  }

  /** Returns the web starter's answers in an environment after this starter's defaults. */
  private ErrorResponseFactory answersFor(MockEnvironment environment, boolean backwardCompatible) {
    new RestClientEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication());
    return new ErrorResponseFactory(WebProperties.resolve(environment), backwardCompatible);
  }

  /** Checks a body is Stratum's 500 with the code, and holds no part of the other service's. */
  private void assertStandardBody(ProblemDetail body, HttpClientErrorCode expected) {
    assertThat(body.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    assertThat(body.getProperties()).containsEntry(WebConstants.CODE, expected.getCode());
    assertThat(body.getDetail()).isEqualTo(expected.getDescription());
    assertThat(String.valueOf(body)).doesNotContain(RestClientTestData.SECRET);
    assertThat(String.valueOf(body)).doesNotContain(RestClientTestData.ORDERS);
  }

  /** Returns the failure of a call that ended with a code, with no detail. */
  private StratumException code(HttpClientErrorCode code) {
    return new StratumException(
        code, "", new ResourceAccessException("no answer", new ConnectException("refused")));
  }
}
