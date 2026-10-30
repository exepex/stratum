package com.exepex.stratum.webmvc.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.webmvc.testsupport.OrderErrorCode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@DisplayName("A service's own handler of StratumException wins over Stratum's")
class ServiceStratumExceptionHandlerIntegrationTest {

  @Test
  @DisplayName("the service's status and body are sent, and the code's mapping is not used")
  void serviceHandlerWins() throws Exception {
    try (var service =
        new SpringApplicationBuilder(ServiceWithOwnHandler.class)
            .run(
                "--server.port=0",
                "--" + WebConstants.ENABLED + "=true",
                "--" + WebConstants.ERROR_STATUSES + ".ORD-0001=409")) {
      var port = ((WebServerApplicationContext) service).getWebServer().getPort();
      var response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
      assertThat(response.body()).isEqualTo("{\"failed\":\"ORD-0001\"}");
    }
  }

  /** A service that answers every StratumException in its own way. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({ServiceWithOwnHandler.OrdersController.class, ServiceWithOwnHandler.OwnHandler.class})
  static class ServiceWithOwnHandler {

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
