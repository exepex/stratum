package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.errors.JsonLibrary;
import com.exepex.stratum.restclient.testsupport.errors.JsonReadFailures;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClientException;

@DisplayName("A JSON body read with Jackson 3 or Jackson 2's converter: bad JSON is an answer")
class JsonBodyFailuresTest {

  /** The bodies that are not valid, each with the type it is read as. */
  private static final List<Arguments> INVALID_BODIES =
      List.of(
          argumentSet("bad JSON, read as a map", JsonReadFailures.MAP, JsonReadFailures.BAD_JSON),
          argumentSet(
              "bad JSON inside a record's list",
              JsonReadFailures.ORDER,
              JsonReadFailures.BAD_JSON_IN_LINES),
          argumentSet(
              "JSON of the wrong type inside a record's list",
              JsonReadFailures.ORDER,
              JsonReadFailures.WRONG_TYPE_IN_LINES));

  /** The translation, for a service with no customizers. */
  private final ClientErrors errors = new ClientErrors(RestClientTestData.noCustomizers());

  @Nested
  @DisplayName("with Jackson 2's converter, a body that is not valid")
  class Jackson2InvalidBody {

    @ParameterizedTest
    @FieldSource("com.exepex.stratum.restclient.JsonBodyFailuresTest#INVALID_BODIES")
    @DisplayName("stays Spring's exception, is never sent again and is not counted by the breaker")
    void isAnAnswer(ParameterizedTypeReference<?> type, String body) {
      assertIsAnAnswer(JsonReadFailures.readFailure(JsonLibrary.JACKSON_2, type, body, null));
    }
  }

  @Nested
  @DisplayName("with Jackson 3, the default, a body that is not valid")
  class Jackson3InvalidBody {

    @ParameterizedTest
    @FieldSource("com.exepex.stratum.restclient.JsonBodyFailuresTest#INVALID_BODIES")
    @DisplayName("stays Spring's exception, is never sent again and is not counted by the breaker")
    void isAnAnswer(ParameterizedTypeReference<?> type, String body) {
      assertIsAnAnswer(JsonReadFailures.readFailure(JsonLibrary.JACKSON_3, type, body, null));
    }
  }

  @Nested
  @DisplayName("a JSON body that ends inside a record's list, with no I/O failure")
  class EndsTooSoon {

    @ParameterizedTest
    @EnumSource(JsonLibrary.class)
    @DisplayName("is a body that is not valid, with either library: the service answered")
    void isAnAnswer(JsonLibrary library) {
      assertIsAnAnswer(
          JsonReadFailures.readFailure(
              library, JsonReadFailures.ORDER, JsonReadFailures.ORDER_START, null));
    }
  }

  @Nested
  @DisplayName("a JSON body cut off by a read timeout inside a record's list")
  class CutOff {

    /** Each library with each type a body cut off inside a record's list is read as. */
    private static final List<Arguments> CUT_OFF_READS =
        List.of(
            argumentSet("Jackson 2, read as a map", JsonLibrary.JACKSON_2, JsonReadFailures.MAP),
            argumentSet(
                "Jackson 2, read as a record", JsonLibrary.JACKSON_2, JsonReadFailures.ORDER),
            argumentSet("Jackson 3, read as a map", JsonLibrary.JACKSON_3, JsonReadFailures.MAP),
            argumentSet(
                "Jackson 3, read as a record", JsonLibrary.JACKSON_3, JsonReadFailures.ORDER));

    @ParameterizedTest
    @FieldSource("CUT_OFF_READS")
    @DisplayName("ends with SX-HTTPC-0001, resends a GET but not a POST, and is counted")
    void endsWithTimeout(JsonLibrary library, ParameterizedTypeReference<?> type) {
      var failure =
          JsonReadFailures.readFailure(
              library,
              type,
              JsonReadFailures.ORDER_START,
              new SocketTimeoutException("Read timed out"));

      assertThat(errors.noAnswer(failure))
          .get()
          .satisfies(
              code ->
                  assertThat(code.getErrorCode())
                      .isEqualTo(HttpClientErrorCode.DOWNSTREAM_TIMEOUT));
      assertThat(RetryRules.isRetryable(RestClientTestData.attempt(HttpMethod.GET, failure)))
          .isTrue();
      assertThat(RetryRules.isRetryable(RestClientTestData.attempt(HttpMethod.POST, failure)))
          .isFalse();
      assertThat(RetryRules.countsAsBreakerFailure(failure)).isTrue();
    }
  }

  /** Checks that a failure is an answer: no code, no other attempt, not counted by the breaker. */
  private void assertIsAnAnswer(RestClientException failure) {
    assertThat(errors.noAnswer(failure)).isEmpty();
    assertThat(RetryRules.isRetryable(RestClientTestData.attempt(HttpMethod.GET, failure)))
        .isFalse();
    assertThat(RetryRules.countsAsBreakerFailure(failure)).isFalse();
  }
}
