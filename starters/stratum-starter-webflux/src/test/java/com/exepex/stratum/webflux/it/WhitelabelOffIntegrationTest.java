package com.exepex.stratum.webflux.it;

import static com.exepex.stratum.test.WebTestSupport.getFromService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.MediaType.TEXT_HTML_VALUE;

import com.exepex.stratum.test.WebTestSupport;
import com.exepex.stratum.web.WebErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

@SpringBootTest(
    classes = OrdersApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "stratum.backward-compatible=false",
      "stratum.web.error.statuses.ORD-0001=409",
      "spring.web.error.whitelabel.enabled=false",
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
    WebTestSupport.checkProblemDetails(
        getFromService(port, DUPLICATE_PATH, ACCEPT, TEXT_HTML_VALUE),
        HttpStatus.CONFLICT.value(),
        "ORD-0001");
  }

  @Test
  @DisplayName("a StratumException is logged once, though the page falls back to the JSON body")
  void loggedOnce(CapturedOutput output) throws Exception {
    getFromService(port, DUPLICATE_PATH, ACCEPT, TEXT_HTML_VALUE);

    assertThat(output.getAll()).containsOnlyOnce("ORD-0001");
  }

  @Test
  @DisplayName("an unknown path gets SX-WEB-1003")
  void unknownPath() throws Exception {
    WebTestSupport.checkProblemDetails(
        getFromService(port, "/no-such-path", ACCEPT, TEXT_HTML_VALUE),
        HttpStatus.NOT_FOUND.value(),
        WebErrorCode.NOT_FOUND.getCode());
  }
}
