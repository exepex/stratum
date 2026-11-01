package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.web.testsupport.WebTestData;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

@DisplayName("ReferencePage shows the status and the trace ID, and nothing a caller could inject")
class ReferencePageTest {

  @Test
  @DisplayName("the page holds the title, the status and the trace ID as the reference")
  void showsStatusAndReference() {
    var page =
        ReferencePage.of(
            Map.of(
                "title",
                "Not Found",
                "status",
                HttpStatus.NOT_FOUND.value(),
                LoggingContextConstants.TRACE_ID,
                WebTestData.TRACE_ID));

    assertThat(page)
        .contains("<h1>Not Found</h1>", "(status 404)")
        .contains("Reference: <code>" + WebTestData.TRACE_ID + "</code>");
  }

  @Test
  @DisplayName("every value is HTML-escaped, and there is no reference without a trace ID")
  void escapesValues() {
    var page =
        ReferencePage.of(
            Map.of("title", "<script>alert(1)</script>", "status", HttpStatus.BAD_REQUEST.value()));

    assertThat(page)
        .doesNotContain("<script>")
        .contains("&lt;script&gt;")
        .doesNotContain("Reference:");
  }

  @Test
  @DisplayName("Spring Boot's own error model shows its reason phrase as the title")
  void showsBootReasonPhrase() {
    var page =
        ReferencePage.of(Map.of("error", "Not Found", "status", HttpStatus.NOT_FOUND.value()));

    assertThat(page).contains("<title>Not Found</title>", "<h1>Not Found</h1>");
  }
}
