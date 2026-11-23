package com.exepex.stratum.web;

import static com.exepex.stratum.web.testsupport.WebTestData.CORRELATION_ID;
import static com.exepex.stratum.web.testsupport.WebTestData.TRACE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.web.testsupport.WebTestData;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

@DisplayName("ErrorResponseFactory finishes the error body that a service's own handler returns")
class ErrorResponseFactoryServiceBodyTest {

  /** The factory, with Stratum's defaults. */
  private final ErrorResponseFactory factory =
      new ErrorResponseFactory(WebTestData.defaults().build(), true);

  @Nested
  @DisplayName("adds request IDs")
  class AddRequestIds {

    @Test
    @DisplayName("adds the trace and correlation IDs a body does not have")
    void addsMissingIds() {
      var body = ProblemDetail.forStatus(HttpStatus.CONFLICT);

      factory.addRequestIds(body, TRACE_ID, CORRELATION_ID);

      assertThat(body.getProperties())
          .containsEntry(LoggingContextConstants.TRACE_ID, TRACE_ID)
          .containsEntry(LoggingContextConstants.CORRELATION_ID, CORRELATION_ID);
    }

    @Test
    @DisplayName("keeps an ID the service's handler already set")
    void keepsIdsAlreadySet() {
      var body = ProblemDetail.forStatus(HttpStatus.CONFLICT);
      body.setProperty(LoggingContextConstants.CORRELATION_ID, "set-by-service");

      factory.addRequestIds(body, TRACE_ID, CORRELATION_ID);

      assertThat(body.getProperties())
          .containsEntry(LoggingContextConstants.CORRELATION_ID, "set-by-service");
    }

    @Test
    @DisplayName("leaves out an ID that is missing or unsafe")
    void leavesOutMissingOrUnsafeIds() {
      var body = ProblemDetail.forStatus(HttpStatus.CONFLICT);

      factory.addRequestIds(body, null, "forged\nline");

      assertThat(body.getProperties()).isNull();
    }
  }

  @Nested
  @DisplayName("adds the request path as the instance")
  class AddInstance {

    @Test
    @DisplayName("sets the path, without the query, on a body with no instance")
    void setsPathWhenMissing() {
      var body = ProblemDetail.forStatus(HttpStatus.PAYMENT_REQUIRED);

      factory.addInstanceIfMissing(body, URI.create("http://localhost/orders/refund?id=7"));

      assertThat(body.getInstance()).isEqualTo(URI.create("/orders/refund"));
    }

    @Test
    @DisplayName("keeps an encoded path as it was sent")
    void keepsEncodedPath() {
      var body = ProblemDetail.forStatus(HttpStatus.PAYMENT_REQUIRED);

      factory.addInstanceIfMissing(body, URI.create("http://localhost/orders/a%20b"));

      assertThat(body.getInstance()).hasToString("/orders/a%20b");
    }

    @Test
    @DisplayName("keeps the instance the service's handler set")
    void keepsServiceInstance() {
      var body = ProblemDetail.forStatus(HttpStatus.PAYMENT_REQUIRED);
      body.setInstance(URI.create("urn:refund:7"));

      factory.addInstanceIfMissing(body, URI.create("http://localhost/orders/refund"));

      assertThat(body.getInstance()).isEqualTo(URI.create("urn:refund:7"));
    }
  }
}
