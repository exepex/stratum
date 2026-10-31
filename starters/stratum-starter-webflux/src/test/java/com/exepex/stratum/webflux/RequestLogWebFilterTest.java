package com.exepex.stratum.webflux;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.LogLineCapture;
import com.exepex.stratum.web.RequestFieldsReader;
import com.exepex.stratum.web.RequestLogWriter;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

@DisplayName("RequestLogWebFilter logs a request the caller cancels")
class RequestLogWebFilterTest {

  /** The status of a request cancelled before its response started. */
  private static final int CLIENT_CLOSED_REQUEST = 499;

  /** The request log lines. */
  private final LogLineCapture lines = new LogLineCapture(WebConstants.REQUEST_LOG_LOGGER);

  /** A filter with the default settings. */
  private final RequestLogWebFilter filter = defaultFilter();

  /** Returns a filter with the default settings. */
  private RequestLogWebFilter defaultFilter() {
    var settings = WebProperties.resolve(new MockEnvironment());
    return new RequestLogWebFilter(
        new RequestLogWriter(settings, RequestFieldsReader.from(settings)));
  }

  /** Captures the request log lines. */
  @BeforeEach
  void captureLines() {
    lines.start();
  }

  /** Stops capturing. */
  @AfterEach
  void stopCapturing() {
    lines.stop();
  }

  @Test
  @DisplayName("a request cancelled before its response started writes one line with status 499")
  void cancelledRequest() {
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders/42"));

    filter.filter(exchange, unanswered -> Mono.never()).subscribe().dispose();

    assertThat(lines.fieldsOf(lines.onlyLine()))
        .containsEntry(WebConstants.HTTP_STATUS, CLIENT_CLOSED_REQUEST);
  }
}
