package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.DownstreamException;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.errors.ErrorDownstream;
import com.exepex.stratum.restclient.testsupport.errors.ErrorsApplication;
import com.exepex.stratum.restclient.testsupport.errors.OrdersClient;
import java.net.URI;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = ErrorsApplication.class)
@Timeout(30)
@DisplayName("A client's error answer is a DownstreamException, read at most 64 KB")
class ClientErrorsIntegrationTest {

  /** A path value of the failed call, which the message never shows. */
  private static final String ORDER_ID = "o-1742";

  /** A query value of the failed call, which the message never shows. */
  private static final String CUSTOMER = "c-9931";

  /** A trace ID in the format the logs accept. */
  private static final String TRACE_ID = "4bf92f3577b34da6";

  /** The orders service. */
  @AutoClose private static final ErrorDownstream DOWNSTREAM = ErrorDownstream.start();

  @Autowired private OrdersClient orders;

  /**
   * Points the orders client at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    registry.add(RestClientTestData.ORDERS_URL, DOWNSTREAM::url);
  }

  @Nested
  @DisplayName("an error answer")
  class ErrorAnswers {

    @Test
    @DisplayName("is a DownstreamException with its status, client, endpoint, headers and body")
    void errorAnswerIsDownstreamException() {
      assertThatExceptionOfType(DownstreamException.class)
          .isThrownBy(() -> orders.order(ORDER_ID, CUSTOMER))
          .satisfies(
              failure -> {
                assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                assertThat(failure.getClient()).isEqualTo(RestClientTestData.ORDERS);
                assertThat(failure.getEndpoint()).isEqualTo("GET /failed/{id}");
                assertThat(failure.getResponseHeaders().getContentType())
                    .isEqualTo(MediaType.TEXT_PLAIN);
                assertThat(failure.getResponseBodyAsString()).contains(RestClientTestData.SECRET);
                assertThat(failure.getCause()).isNull();
              });
    }

    @Test
    @DisplayName("names no body, path value or query value in its message or text")
    void textHidesBodyAndValues() {
      assertThatExceptionOfType(DownstreamException.class)
          .isThrownBy(() -> orders.order(ORDER_ID, CUSTOMER))
          .satisfies(
              failure ->
                  assertThat(failure.getMessage() + failure)
                      .isEqualTo(
                          "orders answered 500 to GET /failed/{id}"
                              + DownstreamException.class.getName()
                              + ": orders answered 500 to GET /failed/{id}")
                      .doesNotContain(RestClientTestData.SECRET, ORDER_ID, CUSTOMER));
    }

    @Test
    @DisplayName("of a call given a whole URI names only its method, never the URI's values")
    void wholeUriNamesMethodOnly() {
      var order = URI.create(DOWNSTREAM.url() + "/failed/" + ORDER_ID + "?customer=" + CUSTOMER);

      assertThatExceptionOfType(DownstreamException.class)
          .isThrownBy(() -> orders.orderAt(order))
          .satisfies(
              failure -> {
                assertThat(failure.getEndpoint()).isEqualTo("GET");
                assertThat(failure.getMessage()).doesNotContain(ORDER_ID, CUSTOMER);
              });
    }

    @Test
    @DisplayName("carries the trace ID of the call")
    void carriesTraceId() {
      MDC.put(LoggingContextConstants.TRACE_ID, TRACE_ID);
      try {
        assertThatExceptionOfType(DownstreamException.class)
            .isThrownBy(() -> orders.order(ORDER_ID, CUSTOMER))
            .satisfies(failure -> assertThat(failure.getTraceId()).contains(TRACE_ID));
      } finally {
        MDC.remove(LoggingContextConstants.TRACE_ID);
      }
    }

    @Test
    @DisplayName("reads the code of a problem detail, and its body with the client's converters")
    void downstreamCodeRead() {
      assertThatExceptionOfType(DownstreamException.class)
          .isThrownBy(orders::missing)
          .satisfies(
              failure -> {
                assertThat(failure.getDownstreamCode()).contains(ErrorDownstream.DOWNSTREAM_CODE);
                assertThat(failure.getResponseBodyAs(ProblemDetail.class).getTitle())
                    .isEqualTo("Not Found");
              });
    }

    @Test
    @DisplayName("has no code when its body is not a problem detail, or is not JSON")
    void noDownstreamCodeOtherwise() {
      assertThatExceptionOfType(DownstreamException.class)
          .isThrownBy(orders::missingUnreadable)
          .satisfies(failure -> assertThat(failure.getDownstreamCode()).isEmpty());
      assertThatExceptionOfType(DownstreamException.class)
          .isThrownBy(() -> orders.order(ORDER_ID, CUSTOMER))
          .satisfies(failure -> assertThat(failure.getDownstreamCode()).isEmpty());
    }
  }

  @Nested
  @DisplayName("the 64 KB read of an error body")
  class BodyLimit {

    @Test
    @DisplayName("a 1 MB error body: 64 KB is kept, and the connection is used again")
    void largeErrorBodyCapped() {
      var connectionsBefore = DOWNSTREAM.connections();

      for (var call = 0; call < 2; call++) {
        assertThatExceptionOfType(DownstreamException.class)
            .isThrownBy(orders::largeError)
            .satisfies(
                failure ->
                    assertThat(failure.getResponseBodyAsByteArray())
                        .hasSize(HttpClientConstants.ERROR_BODY_LIMIT));
      }
      assertThat(DOWNSTREAM.connections() - connectionsBefore).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a gzip error body that is 1 MB once undone: 64 KB is kept")
    void gzipErrorBodyCappedAfterInflate() {
      assertThatExceptionOfType(DownstreamException.class)
          .isThrownBy(orders::largeGzipError)
          .satisfies(
              failure ->
                  assertThat(failure.getResponseBodyAsByteArray())
                      .hasSize(HttpClientConstants.ERROR_BODY_LIMIT));
    }

    @Test
    @DisplayName("an answer that succeeds is read whole")
    void successBodyWhole() {
      assertThat(orders.largeAnswer()).hasSize(ErrorDownstream.LARGE_SIZE);
    }
  }
}
