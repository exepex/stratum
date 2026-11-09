package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.web.ErrorResponseFactory;
import com.exepex.stratum.web.WebProperties;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@DisplayName("The codes of a call get their status from the web starter's map, which the team wins")
class ClientErrorStatusesTest {

  /** The key of the unavailable code's status, as a team writes it in a properties file. */
  private static final String TEAM_UNAVAILABLE_STATUS =
      HttpClientConstants.WEB_ERROR_STATUSES
          + "."
          + HttpClientErrorCode.DOWNSTREAM_UNAVAILABLE.getCode();

  @Test
  @DisplayName("a timeout answers 504, no answer and an open breaker 503, with no downstream named")
  void defaultStatuses() {
    var answers = answersFor(new MockEnvironment());

    assertThat(answers.forFailureWithErrorCode(code(HttpClientErrorCode.DOWNSTREAM_TIMEOUT)))
        .get()
        .satisfies(
            body -> assertThat(body.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT.value()))
        .satisfies(body -> assertThat(body.getDetail()).doesNotContain(RestClientTestData.ORDERS));
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
    var answers = answersFor(new MockEnvironment().withProperty(TEAM_UNAVAILABLE_STATUS, "502"));

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
            answersFor(environment).forFailureWithErrorCode(code(HttpClientErrorCode.BREAKER_OPEN)))
        .get()
        .extracting(ProblemDetail::getStatus)
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
  }

  @Test
  @DisplayName("an uncaught error answer answers 500 and never shows the downstream's body")
  void uncaughtDownstreamIs500WithoutItsBody() {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.TEXT_PLAIN);
    var downstream =
        new DownstreamException(
            RestClientTestData.ORDERS,
            "GET /orders/{id}",
            HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Service Unavailable",
                headers,
                RestClientTestData.SECRET.getBytes(StandardCharsets.UTF_8),
                null));
    var answers = answersFor(new MockEnvironment());

    assertThat(answers.forFailureWithErrorCode(downstream)).isEmpty();
    var body = answers.forFailedRequest(HttpStatus.INTERNAL_SERVER_ERROR, downstream);
    assertThat(body.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    assertThat(answers.membersOf(body).toString()).doesNotContain(RestClientTestData.SECRET);
  }

  /** Returns the web starter's answers in an environment after this starter's defaults. */
  private ErrorResponseFactory answersFor(MockEnvironment environment) {
    new RestClientEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication());
    return new ErrorResponseFactory(WebProperties.resolve(environment), false);
  }

  /** Returns the failure of a call that ended with a code, with no detail. */
  private StratumException code(HttpClientErrorCode code) {
    return new StratumException(
        code, "", new ResourceAccessException("no answer", new ConnectException("refused")));
  }
}
