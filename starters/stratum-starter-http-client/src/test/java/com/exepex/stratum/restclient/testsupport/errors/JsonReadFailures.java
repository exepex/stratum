package com.exepex.stratum.restclient.testsupport.errors;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The failures Spring's {@link RestClient} throws when it reads a JSON body that is not valid or
 * that is cut off, made by the real converters, so that no test writes their causes by hand.
 */
@UtilityClass
public class JsonReadFailures {

  /** A body that is not JSON at all. */
  public static final String BAD_JSON = "{not json";

  /** An order whose bad JSON is inside its list of lines. */
  public static final String BAD_JSON_IN_LINES = "{\"id\":\"o-1\",\"lines\":[{\"sku\":nope}]}";

  /** An order whose quantity is text, not the number its type needs. */
  public static final String WRONG_TYPE_IN_LINES =
      "{\"id\":\"o-1\",\"lines\":[{\"sku\":\"a\",\"quantity\":\"many\"}]}";

  /** The start of an order that stops inside its second line. */
  public static final String ORDER_START =
      "{\"id\":\"o-1\",\"lines\":[{\"sku\":\"a\",\"quantity\":1},{\"sku\":\"";

  /** A body read as a map. */
  public static final ParameterizedTypeReference<Map<String, Object>> MAP =
      new ParameterizedTypeReference<>() {};

  /** A body read as an {@link Order}, a record with a list of records. */
  public static final ParameterizedTypeReference<Order> ORDER =
      ParameterizedTypeReference.forType(Order.class);

  /** Where the stubbed call goes; nothing is sent. */
  private static final URI ANY_URI = URI.create("http://127.0.0.1:9/orders/o-1");

  /**
   * Returns the failure of reading a 200 JSON answer whose body is the given text, and then either
   * ends or fails with the given I/O failure, as a read timeout would.
   *
   * @param library the JSON library the client reads with
   * @param type the type the body is read as
   * @param body the bytes the answer delivers
   * @param cutOffBy the failure after those bytes, or {@code null} for a body that just ends
   * @return Spring's failure, as the client's call throws it
   */
  public static RestClientException readFailure(
      JsonLibrary library,
      ParameterizedTypeReference<?> type,
      String body,
      @Nullable IOException cutOffBy) {
    var client =
        RestClient.builder()
            .requestFactory((uri, method) -> answering(body, cutOffBy))
            .configureMessageConverters(
                converters -> converters.withJsonConverter(library.converter()))
            .build();
    try {
      client.get().uri(ANY_URI).retrieve().body(type);
    } catch (RestClientException failure) {
      return failure;
    }
    throw new AssertionError("The body was read without a failure");
  }

  /** Returns a request whose answer is a 200 JSON body of the given bytes and failure. */
  private static ClientHttpRequest answering(String body, @Nullable IOException cutOffBy) {
    // The answer owns the body's streams and closes them when the client is done with it.
    var response =
        new MockClientHttpResponse(
            new SequenceInputStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                afterBody(cutOffBy)),
            HttpStatus.OK);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    var request = new MockClientHttpRequest();
    request.setResponse(response);
    return request;
  }

  /** Returns what follows the body's bytes: the failure, or the end when there is none. */
  private static InputStream afterBody(@Nullable IOException cutOffBy) {
    return cutOffBy == null ? InputStream.nullInputStream() : new FailingStream(cutOffBy);
  }

  /** A stream whose every read fails with the given I/O failure. */
  @RequiredArgsConstructor
  private static final class FailingStream extends InputStream {

    /** The failure every read throws. */
    private final IOException failure;

    @Override
    public int read() throws IOException {
      throw failure;
    }
  }
}
