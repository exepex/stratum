package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.httpclient.ClientInterceptor;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.testsupport.headers.HeadersTestData;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.StreamingHttpOutputMessage.Body;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.context.request.RequestContextHolder;

@DisplayName("A call runs its interceptors on its copied headers and a copy of its body")
class DefaultOutgoingRequestTest {

  /** What the interceptor under test throws. */
  private static final IllegalStateException KEY_SERVICE_DOWN =
      new IllegalStateException("the key service is down");

  /** The body of an order, as a converter writes it. */
  private static final byte[] ORDER = "{\"sku\":\"A-1\"}".getBytes(StandardCharsets.UTF_8);

  /** The caller's tenant, copied from the request the service answers. */
  private static final String TENANT_VALUE = "acme";

  /** The request of the client's own factory, which must never be sent here. */
  private final ClientHttpRequest delegate = Mockito.mock(ClientHttpRequest.class);

  /** Returns the request of a call whose one interceptor throws the failure given. */
  private DefaultOutgoingRequest requestFailingWith(Throwable failure) {
    return request(delegate, request -> throwUnchecked(failure));
  }

  /**
   * Returns a call that sends the request given, copies the tenant header and runs the one
   * interceptor given.
   */
  private DefaultOutgoingRequest request(ClientHttpRequest sent, ClientInterceptor only) {
    return new DefaultOutgoingRequest(
        "orders",
        sent,
        new CopiedHeaders(List.of(HeadersTestData.TENANT)),
        List.of(only),
        HttpClientConstants.DEFAULT_INTERCEPTOR_BODY_LIMIT,
        TraceHeaders.of(true, new MockEnvironment()));
  }

  /** Returns a request of the client's own factory that answers 200 when it is sent. */
  private MockClientHttpRequest answered(HttpMethod method) {
    var sent = new MockClientHttpRequest(method, URI.create("/orders"));
    sent.setResponse(new MockClientHttpResponse());
    return sent;
  }

  /** Returns a POST whose body an interceptor may read, as a client's interface method gives. */
  private MockClientHttpRequest readablePost() {
    var sent = answered(HttpMethod.POST);
    sent.getAttributes().put(HttpClientConstants.BODY_READABLE, true);
    return sent;
  }

  /** Returns a repeatable body, as Spring's converters give, that the writer given writes. */
  private Body repeatable(Body writer) {
    return new Body() {
      @Override
      public void writeTo(OutputStream out) throws IOException {
        writer.writeTo(out);
      }

      @Override
      public boolean repeatable() {
        return true;
      }
    };
  }

  /** Fails a converter's write with the failure given. */
  private void failWrite(IOException failure) throws IOException {
    throw failure;
  }

  /** Throws an unchecked failure, a runtime exception or an error, as it is. */
  private void throwUnchecked(Throwable failure) {
    if (failure instanceof Error error) {
      throw error;
    }
    throw (RuntimeException) failure;
  }

  @Nested
  @DisplayName("on a call under Stratum's retries and breaker")
  class Marked {

    @Test
    @DisplayName("the failure is carried, with the interceptor's own failure inside, unsent")
    void failureCarried() throws IOException {
      var request = requestFailingWith(KEY_SERVICE_DOWN);

      assertThatThrownBy(request::execute)
          .isInstanceOf(InterceptorFailure.class)
          .cause()
          .isSameAs(KEY_SERVICE_DOWN);
      Mockito.verify(delegate, Mockito.never()).execute();
    }

    @Test
    @DisplayName("a failure already carried, by a nested call, is not carried twice")
    void carriedOnce() {
      var nested = new InterceptorFailure(KEY_SERVICE_DOWN);
      var request = requestFailingWith(nested);

      assertThatThrownBy(request::execute).isSameAs(nested);
    }

    @Test
    @DisplayName("an Error is neither caught nor carried")
    void errorNotCaught() {
      var error = new AssertionError("the interceptor broke");
      var request = requestFailingWith(error);

      assertThatThrownBy(request::execute).isSameAs(error);
    }
  }

  @Nested
  @DisplayName("an interceptor reading the body")
  class ReadBody {

    @Test
    @DisplayName("gets a copy: changing it changes neither a later read nor the body sent")
    void bodyCopied() throws IOException {
      var sent = readablePost();
      var request = request(sent, outgoing -> outgoing.body().orElseThrow()[0] = 'X');
      request.setBody(repeatable(out -> out.write(ORDER)));

      request.execute();

      assertThat(request.body().orElseThrow()).isEqualTo(ORDER);
      assertThat(sent.getBodyAsBytes()).isEqualTo(ORDER);
    }

    @Test
    @DisplayName("a converter failure that is not the limit stays Spring's own failure")
    void converterFailureKept() {
      var failure = new HttpMessageNotWritableException("no converter for Order");
      var request = request(readablePost(), outgoing -> outgoing.body());
      request.setBody(repeatable(out -> throwUnchecked(failure)));

      assertThatThrownBy(request::body).isSameAs(failure);
    }

    @Test
    @DisplayName("a write failure that is not the limit is thrown, unchecked, with it inside")
    void writeFailureKept() {
      var failure = new IOException("disk full");
      var request = request(readablePost(), outgoing -> outgoing.body());
      request.setBody(repeatable(out -> failWrite(failure)));

      assertThatThrownBy(request::body)
          .isInstanceOf(UncheckedIOException.class)
          .cause()
          .isSameAs(failure);
    }
  }

  @Nested
  @DisplayName("while the service answers a request")
  class Copied {

    /** Forgets the request the test bound. */
    @AfterEach
    void unbindRequest() {
      RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("the copied headers are on the call before the interceptors run")
    void headersCopiedFirst() throws IOException {
      HeadersTestData.answering(HeadersTestData.TENANT, TENANT_VALUE);
      var seen = new AtomicReference<Optional<String>>();
      var request =
          request(
              answered(HttpMethod.GET),
              outgoing -> seen.set(outgoing.header(HeadersTestData.TENANT)));

      request.execute();

      assertThat(seen.get()).contains(TENANT_VALUE);
    }
  }
}
