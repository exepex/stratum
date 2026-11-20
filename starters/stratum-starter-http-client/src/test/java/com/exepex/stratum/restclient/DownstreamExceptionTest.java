package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.http.HttpStatus;

@DisplayName("DownstreamException carries the code of the error answer it holds")
class DownstreamExceptionTest {

  @Nested
  @DisplayName("its code")
  class Code {

    /** The statuses the other service may accept later, after which the code is SX-HTTPC-0009. */
    private static final List<Arguments> REFUSED_FOR_NOW =
        List.of(
            argumentSet("429", HttpStatus.TOO_MANY_REQUESTS),
            argumentSet("502", HttpStatus.BAD_GATEWAY),
            argumentSet("503", HttpStatus.SERVICE_UNAVAILABLE),
            argumentSet("504", HttpStatus.GATEWAY_TIMEOUT));

    /** Final error answers, after which the code is SX-HTTPC-0007. */
    private static final List<Arguments> FINAL =
        List.of(
            argumentSet("400", HttpStatus.BAD_REQUEST),
            argumentSet("401", HttpStatus.UNAUTHORIZED),
            argumentSet("404", HttpStatus.NOT_FOUND),
            argumentSet("409", HttpStatus.CONFLICT),
            argumentSet("500", HttpStatus.INTERNAL_SERVER_ERROR),
            argumentSet("501", HttpStatus.NOT_IMPLEMENTED),
            argumentSet("505", HttpStatus.HTTP_VERSION_NOT_SUPPORTED));

    @ParameterizedTest
    @FieldSource("REFUSED_FOR_NOW")
    @DisplayName("is SX-HTTPC-0009 for 429, 502, 503 and 504")
    void refusedForNow(HttpStatus status) {
      assertThat(RestClientTestData.downstream(status).getErrorCode())
          .isEqualTo(HttpClientErrorCode.DOWNSTREAM_REFUSED_FOR_NOW);
    }

    @ParameterizedTest
    @FieldSource("FINAL")
    @DisplayName("is SX-HTTPC-0007 for any other status")
    void finalAnswer(HttpStatus status) {
      assertThat(RestClientTestData.downstream(status).getErrorCode())
          .isEqualTo(HttpClientErrorCode.DOWNSTREAM_ERROR);
    }
  }

  @Nested
  @DisplayName("what its caller is told")
  class Report {

    @Test
    @DisplayName("has no detail, so its report names neither the client nor the answer's body")
    void reportHidesTheOtherService() {
      var failure = RestClientTestData.downstream(HttpStatus.SERVICE_UNAVAILABLE);

      assertThat(failure.getErrorDetail()).isEmpty();
      assertThat(failure.getReport().getExternal().message())
          .isEqualTo(HttpClientErrorCode.DOWNSTREAM_REFUSED_FOR_NOW.getDescription())
          .doesNotContain(RestClientTestData.ORDERS)
          .doesNotContain(RestClientTestData.SECRET);
    }

    @Test
    @DisplayName("keeps the other service's status, headers and body for the service's own code")
    void keepsTheAnswer() {
      var failure = RestClientTestData.downstream(HttpStatus.TOO_MANY_REQUESTS);

      assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
      assertThat(failure.getResponseBodyAsString()).isEqualTo(RestClientTestData.SECRET);
      assertThat(failure.getClient()).isEqualTo(RestClientTestData.ORDERS);
    }
  }
}
