package com.exepex.stratum.webflux.it;

import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebErrorCode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

@DisplayName("With Spring Boot's default error page off, a browser gets Stratum's body and status")
class WhitelabelOffIntegrationTest {

  /** The running service. */
  private static ConfigurableApplicationContext service;

  /** Starts the service on a free port, with the default error page off and tracing on. */
  @BeforeAll
  static void startService() {
    service =
        new SpringApplicationBuilder(OrdersApplication.class)
            .run(
                "--server.port=0",
                "--" + WebConstants.ENABLED + "=true",
                "--" + WebConstants.ERROR_STATUSES + ".ORD-0001=409",
                "--spring.web.error.whitelabel.enabled=false",
                "--stratum.observability.enabled=true",
                "--management.tracing.sampling.probability=1.0",
                "--management.tracing.export.enabled=false",
                "--management.otlp.metrics.export.enabled=false");
  }

  /** Stops the service. */
  @AfterAll
  static void stopService() {
    service.close();
  }

  @Test
  @DisplayName("a mapped StratumException keeps its mapped status, not 500")
  void mappedStatusKept() throws Exception {
    StarterTestSupport.checkProblemDetails(
        getHtml("/orders/duplicate"), HttpStatus.CONFLICT.value(), "ORD-0001");
  }

  @Test
  @DisplayName("an unknown path gets SX-WEB-1003")
  void unknownPath() throws Exception {
    StarterTestSupport.checkProblemDetails(
        getHtml("/no-such-path"), HttpStatus.NOT_FOUND.value(), WebErrorCode.NOT_FOUND.getCode());
  }

  /** Sends a GET as a browser does and reads the body as text. */
  private HttpResponse<String> getHtml(String path) throws IOException, InterruptedException {
    var port = ((WebServerApplicationContext) service).getWebServer().getPort();
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE)
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
