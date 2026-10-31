package com.exepex.stratum.webmvc.it;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** A service whose endpoints return the logging context their log lines would carry. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(RequestFieldsApplication.FieldsController.class)
public class RequestFieldsApplication {

  /** The endpoints. */
  @RestController
  static class FieldsController {

    /** Calls this service, as a request calls another service. */
    private final RestClient selfClient;

    /** The running server, whose port the client calls. */
    private final WebServerApplicationContext server;

    /** Spring Boot's task executor, which carries the logging context. */
    private final AsyncTaskExecutor taskExecutor;

    /**
     * Makes the controller.
     *
     * @param restClientBuilder Spring Boot's builder, which sends the trace and baggage
     * @param server the running server
     * @param taskExecutor Spring Boot's task executor
     */
    FieldsController(
        RestClient.Builder restClientBuilder,
        WebServerApplicationContext server,
        AsyncTaskExecutor taskExecutor) {
      this.selfClient = restClientBuilder.build();
      this.server = server;
      this.taskExecutor = taskExecutor;
    }

    /**
     * Returns the logging context of work the request hands to another thread.
     *
     * @param orderId the order's ID
     * @return the logging context on the other thread
     */
    @GetMapping("/orders/{orderId}/async-fields")
    CompletableFuture<Map<String, String>> asyncFields(@PathVariable String orderId) {
      return taskExecutor.submitCompletable(
          () -> Objects.requireNonNullElse(MDC.getCopyOfContextMap(), Map.of()));
    }

    /**
     * Returns the logging context of the request.
     *
     * @param orderId the order's ID
     * @return the logging context
     */
    @GetMapping("/orders/{orderId}/fields")
    Map<String, String> fields(@PathVariable String orderId) {
      return Objects.requireNonNullElse(MDC.getCopyOfContextMap(), Map.of());
    }

    /**
     * Calls this service's echo endpoint and returns what it received.
     *
     * @return the baggage header the called service received
     */
    @GetMapping("/downstream")
    String downstream() {
      return selfClient
          .get()
          .uri("http://localhost:" + server.getWebServer().getPort() + "/echo-baggage")
          .retrieve()
          .body(String.class);
    }

    /**
     * Returns the baggage header this request arrived with, as plain text so a browser never runs
     * it.
     *
     * @param baggage the W3C baggage header, if any
     * @return the header, or an empty string
     */
    @GetMapping(path = "/echo-baggage", produces = MediaType.TEXT_PLAIN_VALUE)
    String echoBaggage(
        @RequestHeader(name = "baggage", required = false) @Nullable String baggage) {
      return Objects.requireNonNullElse(baggage, "");
    }
  }
}
