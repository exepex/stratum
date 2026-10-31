package com.exepex.stratum.webmvc;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.web.RequestFieldsAccessor;
import com.exepex.stratum.web.RequestFieldsReader;
import com.exepex.stratum.web.WebProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName(
    "RequestFieldsFilter puts the request fields in the logging context only while it runs")
class RequestFieldsFilterTest {

  /** A filter with the default settings. */
  private final RequestFieldsFilter filter =
      new RequestFieldsFilter(
          RequestFieldsReader.from(WebProperties.resolve(new MockEnvironment())),
          new RequestFieldsAccessor());

  /** Leaves no field behind for the next test, whatever this one did. */
  @AfterEach
  void clearLoggingContext() {
    MDC.clear();
  }

  @Test
  @DisplayName("the fields are there for the rest of the chain and gone when the request ends")
  void removesFieldsWhenRequestEnds() throws ServletException, IOException {
    var fieldsSeenByChain = new HashMap<String, String>();
    FilterChain chain =
        (request, response) ->
            fieldsSeenByChain.putAll(
                Objects.requireNonNullElse(MDC.getCopyOfContextMap(), Map.of()));

    filter.doFilter(
        new MockHttpServletRequest("GET", "/orders/42"), new MockHttpServletResponse(), chain);

    assertThat(fieldsSeenByChain)
        .containsEntry(LoggingContextConstants.HTTP_METHOD, "GET")
        .containsEntry(LoggingContextConstants.HTTP_PATH, "/orders/42");
    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }
}
