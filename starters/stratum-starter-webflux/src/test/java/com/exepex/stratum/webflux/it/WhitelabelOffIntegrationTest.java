package com.exepex.stratum.webflux.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.WebErrorCode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

@SpringBootTest(
    classes = OrdersApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "stratum.web.enabled=true",
      "stratum.web.backward-compatible=false",
      "stratum.web.error.statuses.ORD-0001=409",
      "spring.web.error.whitelabel.enabled=false",
      "stratum.observability.enabled=true",
      "management.tracing.sampling.probability=1.0",
      "management.tracing.export.enabled=false",
      "management.otlp.metrics.export.enabled=false"
    })
@DisplayName("With Spring Boot's default error page off, a browser gets Stratum's body and status")
@ExtendWith(OutputCaptureExtension.class)
class WhitelabelOffIntegrationTest {

  /** The path that fails with a code the service maps to 409. */
  private static final String DUPLICATE_PATH = "/orders/duplicate";

  /** The port the service listens on. */
  @LocalServerPort private int port;

  @Test
  @DisplayName("a mapped StratumException keeps its mapped status, not 500")
  void mappedStatusKept() throws Exception {
    StarterTestSupport.checkProblemDetails(
        getHtml(DUPLICATE_PATH), HttpStatus.CONFLICT.value(), "ORD-0001");
  }

  @Test
  @DisplayName("a StratumException is logged once, though the page falls back to the JSON body")
  void loggedOnce(CapturedOutput output) throws Exception {
    getHtml(DUPLICATE_PATH);

    assertThat(output.getAll()).containsOnlyOnce("ORD-0001");
  }

  @Test
  @DisplayName("an unknown path gets SX-WEB-1003")
  void unknownPath() throws Exception {
    StarterTestSupport.checkProblemDetails(
        getHtml("/no-such-path"), HttpStatus.NOT_FOUND.value(), WebErrorCode.NOT_FOUND.getCode());
  }

  /** Sends a GET as a browser does and reads the body as text. */
  private HttpResponse<String> getHtml(String path) throws IOException, InterruptedException {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE)
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
