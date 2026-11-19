package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.errors.Order;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.UnknownContentTypeException;
import org.springframework.web.service.invoker.HttpRequestValues;

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

    @Test
    @DisplayName("a body that is not valid is not a call that got no answer")
    void invalidBodyUnchanged() {
      var failure =
          new RestClientException(
              "bad JSON",
              new HttpMessageNotReadableException(
                  "bad JSON", new MockHttpInputMessage(new byte[0])));

      assertThat(errors.noAnswer(failure)).isEmpty();
    }
  }

  @Nested
  @DisplayName("a call whose answer came but cannot be read")
  class UnreadableAnswer {

    /** Spring's failures to read an answer's body, each from one converter or none. */
    private static final List<RestClientException> UNREADABLE =
        List.of(
            new RestClientException(
                "Error while extracting response",
                new HttpMessageNotReadableException(
                    "bad JSON", new MockHttpInputMessage(new byte[0]))),
            new RestClientException(
                "Error while extracting response", new IOException("not protobuf")),
            new UnknownContentTypeException(
                Order.class,
                MediaType.TEXT_PLAIN,
                HttpStatus.OK,
                "OK",
                new HttpHeaders(),
                new byte[0]));

    /** The call, a read of one order. */
    private final HttpRequestValues call =
        HttpRequestValues.builder()
            .setHttpMethod(HttpMethod.GET)
            .setUriTemplate("/orders/{id}")
            .build();

    @ParameterizedTest
    @FieldSource("UNREADABLE")
    @DisplayName("is SX-HTTPC-0008, with Spring's failure as its cause and the endpoint's template")
    void isUnreadableAnswer(RestClientException failure) {
      assertThat(errors.unreadable(RestClientTestData.ORDERS, call, failure))
          .get()
          .satisfies(CallChecks::assertUnreadableAnswer)
          .satisfies(answer -> assertThat(answer).hasCause(failure))
          .satisfies(answer -> assertThat(answer.getEndpoint()).isEqualTo("GET /orders/{id}"));
    }

    @Test
    @DisplayName("a request body no converter writes, with no cause, is not an unreadable answer")
    void unwritableRequestIsNot() {
      var failure = new RestClientException("No HttpMessageConverter for an order");

      assertThat(errors.unreadable(RestClientTestData.ORDERS, call, failure)).isEmpty();
    }
  }

  @Nested
  @DisplayName("its place among Stratum's decorators")
  class DecoratorOrder {

    @Test
    @DisplayName("the decorator is the outermost of Stratum's, outside the retries")
    void outermost() {
      assertThat(errors.getOrder()).isLessThan(HttpClientConstants.RESILIENCE_ORDER);
    }
  }

  /** Returns a check of the code a call that got no answer ends with. */
  private ObjectAssert<?> assertCode(RestClientException failure) {
    return assertThat(errors.noAnswer(failure).orElseThrow().getErrorCode());
  }
}
