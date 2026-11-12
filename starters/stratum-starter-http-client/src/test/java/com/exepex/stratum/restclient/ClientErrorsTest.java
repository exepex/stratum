package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.assertj.core.api.ObjectAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

@DisplayName("ClientErrors turns the failure that ends a call into what the service sees")
class ClientErrorsTest {

  /** The causes of a call that got no answer and was not a timeout once connected. */
  private static final List<IOException> UNAVAILABLE =
      List.of(
          new ConnectException("refused"),
          new ConnectTimeoutException("connect timed out"),
          new UnknownHostException("no such host"),
          new ConnectionRequestTimeoutException("pool full"),
          new NoHttpResponseException("dropped"),
          new SocketException("reset"));

  /** The message of a read timeout. */
  private static final String READ_TIMEOUT = "read timed out";

  /** The translation. */
  private final ClientErrors errors = new ClientErrors(RestClientTestData.noCustomizers());

  @Nested
  @DisplayName("a call that got no answer")
  class NoAnswer {

    @Test
    @DisplayName("ends with SX-HTTPC-0001 after a read timeout, its cause kept")
    void readTimeoutIsTimeout() {
      var failure =
          new ResourceAccessException(READ_TIMEOUT, new SocketTimeoutException(READ_TIMEOUT));

      assertCode(failure).isEqualTo(HttpClientErrorCode.DOWNSTREAM_TIMEOUT);
      assertThat(errors.noAnswer(failure).orElseThrow()).hasCause(failure);
    }

    @ParameterizedTest
    @FieldSource("com.exepex.stratum.restclient.ClientErrorsTest#UNAVAILABLE")
    @DisplayName("ends with SX-HTTPC-0002 for any other cause")
    void otherCausesAreUnavailable(IOException cause) {
      var failure = new ResourceAccessException("no answer", cause);

      assertCode(failure).isEqualTo(HttpClientErrorCode.DOWNSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("a body cut off by a timeout also ends with SX-HTTPC-0001")
    void bodyCutOffByTimeout() {
      var failure = new RestClientException("cut off", new SocketTimeoutException(READ_TIMEOUT));

      assertCode(failure).isEqualTo(HttpClientErrorCode.DOWNSTREAM_TIMEOUT);
    }

    @Test
    @DisplayName(
        "a JSON body cut off by a timeout, three causes deep, also ends with SX-HTTPC-0001")
    void jsonBodyCutOffByTimeout() {
      var failure =
          new RestClientException(
              "cut off",
              new HttpMessageNotReadableException(
                  "cut off",
                  new UncheckedIOException(new SocketTimeoutException(READ_TIMEOUT)),
                  new MockHttpInputMessage(new byte[0])));

      assertCode(failure).isEqualTo(HttpClientErrorCode.DOWNSTREAM_TIMEOUT);
    }

    @Test
    @DisplayName("names no client, path or value to the caller: the code's description only")
    void noDetailForTheCaller() {
      var failure = new ResourceAccessException("no answer", new ConnectException("refused"));

      assertThat(errors.noAnswer(failure))
          .get()
          .satisfies(
              code ->
                  assertThat(code.getReport().getExternal().message())
                      .isEqualTo(HttpClientErrorCode.DOWNSTREAM_UNAVAILABLE.getDescription()));
    }
  }

  @Test
  @DisplayName("a body that is not valid stays Spring's own exception")
  void invalidBodyUnchanged() {
    var failure =
        new RestClientException(
            "bad JSON",
            new HttpMessageNotReadableException("bad JSON", new MockHttpInputMessage(new byte[0])));

    assertThat(errors.noAnswer(failure)).isEmpty();
  }

  @Test
  @DisplayName("the decorator is the outermost of Stratum's, outside the retries")
  void outermost() {
    assertThat(errors.getOrder()).isLessThan(HttpClientConstants.RESILIENCE_ORDER);
  }

  /** Returns a check of the code a call that got no answer ends with. */
  private ObjectAssert<?> assertCode(RestClientException failure) {
    return assertThat(errors.noAnswer(failure).orElseThrow().getErrorCode());
  }
}
