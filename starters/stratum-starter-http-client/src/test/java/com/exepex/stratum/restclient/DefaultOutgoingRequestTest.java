package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.httpclient.ClientInterceptor;
import com.exepex.stratum.httpclient.HttpClientConstants;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.client.ClientHttpRequest;

@DisplayName("A failing interceptor is marked for the retries and breaker, and nothing is sent")
class DefaultOutgoingRequestTest {

  /** What the interceptor under test throws. */
  private static final IllegalStateException KEY_SERVICE_DOWN =
      new IllegalStateException("the key service is down");

  /** The request of the client's own factory, which must never be sent here. */
  private final ClientHttpRequest delegate = Mockito.mock(ClientHttpRequest.class);

  /** Returns the request of a call whose one interceptor throws the failure given. */
  private DefaultOutgoingRequest requestFailingWith(Throwable failure) {
    ClientInterceptor failing = request -> throwUnchecked(failure);
    return new DefaultOutgoingRequest(
        "orders",
        delegate,
        new CopiedHeaders(List.of()),
        List.of(failing),
        HttpClientConstants.DEFAULT_INTERCEPTOR_BODY_LIMIT);
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
}
