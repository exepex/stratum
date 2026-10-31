package com.exepex.stratum.webflux.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.webflux.testsupport.OrderErrorCode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.webflux.error.DefaultErrorAttributes;
import org.springframework.boot.webflux.error.ErrorAttributes;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.server.ServerRequest;

@DisplayName("A service's own error handling wins over Stratum's")
class ServiceErrorHandlingIntegrationTest {

  /** The running service. */
  private static ConfigurableApplicationContext service;

  /** Starts the service on a free port. */
  @BeforeAll
  static void startService() {
    service =
        new SpringApplicationBuilder(ServiceWithOwnHandling.class)
            .run(
                "--server.port=0",
                "--" + WebConstants.ENABLED + "=true",
                "--" + WebConstants.ERROR_STATUSES + ".ORD-0001=409");
  }

  /** Stops the service. */
  @AfterAll
  static void stopService() {
    service.close();
  }

  @Nested
  @DisplayName("when the service's own advice handles StratumException")
  class OwnHandler {

    @Test
    @DisplayName("its StratumException handler's status and body are sent, not the mapped status")
    void serviceHandlerWins() throws Exception {
      var response = get("/orders", MediaType.APPLICATION_JSON);

      assertThat(response.statusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
      assertThat(response.body()).isEqualTo("{\"failed\":\"ORD-0001\"}");
    }
  }

  @Nested
  @DisplayName("when the service has its own error attributes")
  class OwnErrorAttributes {

    @Test
    @DisplayName("its own error attributes keep their body and the plain JSON media type")
    void serviceErrorAttributesKeepTheirMediaType() throws Exception {
      var response = get("/no-such-path", MediaType.APPLICATION_JSON);

      assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
      assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
          .hasValue(MediaType.APPLICATION_JSON_VALUE);
      assertThat(response.body()).isEqualTo("{\"legacy\":\"error\"}");
    }

    @Test
    @DisplayName("its own error attributes give a browser Spring Boot's page, not Stratum's")
    void serviceErrorAttributesKeepBootPage() throws Exception {
      var response = get("/no-such-path", MediaType.TEXT_HTML);

      assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
      assertThat(response.body()).contains("Whitelabel Error Page").doesNotContain("Reference:");
    }
  }

  /** Sends a GET that accepts the given media type and reads the body as text. */
  private HttpResponse<String> get(String path, MediaType accepted)
      throws IOException, InterruptedException {
    var port = ((WebServerApplicationContext) service).getWebServer().getPort();
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header(HttpHeaders.ACCEPT, accepted.toString())
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  /** A service that answers every StratumException, and every other error, in its own way. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({ServiceWithOwnHandling.OrdersController.class, ServiceWithOwnHandling.OwnHandler.class})
  static class ServiceWithOwnHandling {

    /**
     * Returns the service's own error attributes, in the shape its old clients expect.
     *
     * @return the error attributes
     */
    @Bean
    ErrorAttributes legacyErrorAttributes() {
      return new DefaultErrorAttributes() {
        @Override
        public Map<String, Object> getErrorAttributes(
            ServerRequest request, ErrorAttributeOptions options) {
          return Map.of("legacy", "error");
        }
      };
    }

    /** The endpoint. */
    @RestController
    static class OrdersController {

      /**
       * Fails with a code the service also maps in its settings.
       *
       * @return never returns
       */
      @GetMapping("/orders")
      String duplicate() {
        throw new StratumException(OrderErrorCode.DUPLICATE_ORDER);
      }
    }

    /** The service's handler, with no order of its own. */
    @RestControllerAdvice
    static class OwnHandler {

      /**
       * Answers with the code only, in the service's own shape.
       *
       * @param failure the exception
       * @return the body
       */
      @ExceptionHandler(StratumException.class)
      @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
      Map<String, String> answer(StratumException failure) {
        return Map.of("failed", failure.getReport().getExternal().code());
      }
    }
  }
}
