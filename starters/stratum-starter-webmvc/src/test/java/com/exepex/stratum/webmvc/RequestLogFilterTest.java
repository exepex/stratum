package com.exepex.stratum.webmvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.test.LogLineCapture;
import com.exepex.stratum.web.RequestFieldsReader;
import com.exepex.stratum.web.RequestLogWriter;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("RequestLogFilter logs a request whose error no handler answered")
class RequestLogFilterTest {

  /** The request log lines. */
  private final LogLineCapture lines = new LogLineCapture(WebConstants.REQUEST_LOG_LOGGER);

  /** A filter with the default settings. */
  private final RequestLogFilter filter = defaultFilter();

  /** Returns a filter with the default settings. */
  private RequestLogFilter defaultFilter() {
    var settings = WebProperties.resolve(new MockEnvironment());
    return new RequestLogFilter(new RequestLogWriter(settings, RequestFieldsReader.from(settings)));
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
  @DisplayName("a request whose chain fails writes one line with status 500, and the error goes on")
  void failedChain() {
    var request = new MockHttpServletRequest("GET", "/orders/42");

    assertThatThrownBy(
            () ->
                filter.doFilter(
                    request,
                    new MockHttpServletResponse(),
                    (unansweredRequest, unansweredResponse) -> {
                      throw new ServletException("no handler answered");
                    }))
        .isInstanceOf(ServletException.class);

    assertThat(lines.fieldsOf(lines.onlyLine()))
        .containsEntry(WebConstants.HTTP_STATUS, HttpStatus.INTERNAL_SERVER_ERROR.value());
  }
}
