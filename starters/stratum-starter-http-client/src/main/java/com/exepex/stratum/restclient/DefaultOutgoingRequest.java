package com.exepex.stratum.restclient;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.ClientInterceptor;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.httpclient.OutgoingRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.util.unit.DataSize;

/**
 * One attempt of a call of a client with copied headers or interceptors: it wraps the request of
 * the client's own factory, the pool's or the mocks', and when it is sent, copies the headers, runs
 * the interceptors and hands the body on. Its headers and attributes are the wrapped request's, so
 * what Spring sets on it, such as the mocked method, reaches that request. It holds no body in
 * memory unless an interceptor reads one that is not streamed. Its text shows the client, method
 * and path only, never a header, the query or the body.
 */
@RequiredArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
final class DefaultOutgoingRequest
    implements ClientHttpRequest, StreamingHttpOutputMessage, OutgoingRequest {

  /** The client's name. */
  @ToString.Include(name = "client")
  private final String clientName;

  /** The request of the client's own factory, which is sent. */
  private final ClientHttpRequest delegate;

  /** The headers copied from the request the service is answering. */
  private final CopiedHeaders copiedHeaders;

  /** The global interceptors, then the client's own, in the order they run. */
  private final List<ClientInterceptor> interceptors;

  /** The most of the body {@link #body()} reads: the client's {@code interceptor-body-limit}. */
  private final DataSize bodyLimit;

  /** The body Spring's converter gave, handed on when the request is sent; none until then. */
  private final AtomicReference<@Nullable Body> givenBody = new AtomicReference<>();

  /** The body once an interceptor read it, then sent as read; none until then. */
  private byte @Nullable [] readBody;

  @Override
  public HttpMethod getMethod() {
    return delegate.getMethod();
  }

  @Override
  public URI getURI() {
    return delegate.getURI();
  }

  @Override
  public HttpHeaders getHeaders() {
    return delegate.getHeaders();
  }

  @Override
  public Map<String, Object> getAttributes() {
    return delegate.getAttributes();
  }

  /**
   * Returns the wrapped request's body stream, for a converter that writes the body itself.
   *
   * @return the wrapped request's body stream
   * @throws IOException if the wrapped request cannot give it
   */
  @Override
  public OutputStream getBody() throws IOException {
    return delegate.getBody();
  }

  /**
   * Keeps the body until the request is sent, so that an interceptor may read it first.
   *
   * @param body the body
   */
  @Override
  public void setBody(Body body) {
    givenBody.set(body);
  }

  /**
   * Copies the headers, runs the interceptors, hands the body on and sends the wrapped request.
   *
   * @return the answer
   * @throws IOException if the call fails
   */
  @Override
  public ClientHttpResponse execute() throws IOException {
    copiedHeaders.copyInto(getHeaders());
    runInterceptors();
    handOnBody();
    return delegate.execute();
  }

  @Override
  public String client() {
    return clientName;
  }

  @Override
  @ToString.Include
  public String method() {
    return getMethod().name();
  }

  @Override
  public URI uri() {
    return getURI();
  }

  @Override
  public Optional<String> header(String name) {
    return Optional.ofNullable(getHeaders().getFirst(name));
  }

  @Override
  public void setHeader(String name, String value) {
    getHeaders().set(name, value);
  }

  @Override
  public Optional<byte[]> body() {
    var given = givenBody.get();
    if (readBody == null && given != null && given.repeatable() && isMarkedReadable()) {
      readBody = written(given);
    }
    return Optional.ofNullable(readBody).map(byte[]::clone);
  }

  /**
   * Returns the path the call goes to, without its query, which its text shows.
   *
   * @return the path
   */
  @ToString.Include
  String path() {
    return getURI().getRawPath();
  }

  /**
   * Runs the interceptors. A failure of one of them, which is service-written code, is marked as an
   * {@link InterceptorFailure}, so that Stratum's retries and breaker, which every call of a client
   * runs through, see that nothing was sent; the caller still gets the failure as thrown. A failure
   * already marked, by a nested call, is not marked twice. An {@code Error} is never caught.
   */
  private void runInterceptors() {
    try {
      interceptors.forEach(interceptor -> interceptor.beforeSend(this));
    } catch (InterceptorFailure alreadyMarked) {
      throw alreadyMarked;
    } catch (RuntimeException failure) {
      throw new InterceptorFailure(failure);
    }
  }

  /** Returns whether the call's method argument is a body that is not streamed. */
  private boolean isMarkedReadable() {
    return Boolean.TRUE.equals(getAttributes().get(HttpClientConstants.BODY_READABLE));
  }

  /**
   * Returns the bytes a repeatable body writes, at most {@link #bodyLimit} of them. A converter may
   * wrap the stream's refusal, such as Jackson's {@code HttpMessageNotWritableException}, so the
   * refusal is looked for in the failure's causes.
   *
   * @throws StratumException {@code SX-HTTPC-0005} when the body is larger than the limit
   */
  private byte[] written(Body repeatable) {
    try (var limited = new LimitedBodyStream(bodyLimit.toBytes())) {
      try {
        repeatable.writeTo(limited);
      } catch (IOException failure) {
        if (!limited.isLimitReachedIn(failure)) {
          throw new UncheckedIOException(failure);
        }
      } catch (HttpMessageConversionException failure) {
        if (!limited.isLimitReachedIn(failure)) {
          throw failure;
        }
      }
      // The converter's own failure is not kept: its message can name a part of the body.
      if (limited.isLimitReached()) {
        throw tooLarge();
      }
      return limited.toByteArray();
    }
  }

  /**
   * Returns the failure of a call whose body is larger than the limit. The caller sees only the
   * code; the logged cause names the client and the limit in bytes, never a byte of the body, and
   * has no cause of its own.
   */
  private StratumException tooLarge() {
    return new StratumException(
        HttpClientErrorCode.INTERCEPTOR_BODY_TOO_LARGE,
        "",
        new IllegalStateException(
            "client " + clientName + ", interceptor-body-limit " + bodyLimit));
  }

  /**
   * Hands the body to the wrapped request, as an interceptor read it or as Spring gave it, through
   * the request's own streaming, such as the pool's. The only request that does not stream is a
   * mock's, which drops any body, so nothing is handed to it.
   */
  private void handOnBody() throws IOException {
    var given = givenBody.get();
    if (given == null || !(delegate instanceof StreamingHttpOutputMessage streaming)) {
      return;
    }
    if (readBody == null) {
      streaming.setBody(given);
    } else {
      streaming.setBody(readBody);
    }
  }
}
