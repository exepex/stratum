package com.exepex.stratum.webflux.it;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** A reactive service whose endpoints return the logging context their log lines would carry. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(RequestFieldsApplication.FieldsController.class)
public class RequestFieldsApplication {

  /** The endpoints. */
  @RestController
  static class FieldsController {

    /** Calls this service, as a request calls another service. */
    private final WebClient selfClient;

    /** The running server, whose port the client calls. */
    private final WebServerApplicationContext server;

    /**
     * Makes the controller.
     *
     * @param webClientBuilder Spring Boot's builder, which sends the trace and baggage
     * @param server the running server
     */
    FieldsController(WebClient.Builder webClientBuilder, WebServerApplicationContext server) {
      this.selfClient = webClientBuilder.build();
      this.server = server;
    }

    /**
     * Returns the logging context of a step that runs on another thread.
     *
     * @param orderId the order's ID
     * @return the logging context
     */
    @GetMapping("/orders/{orderId}/fields")
    Mono<Map<String, String>> fields(@PathVariable String orderId) {
      return Mono.just(orderId)
          .publishOn(Schedulers.boundedElastic())
          .map(ignored -> Objects.requireNonNullElse(MDC.getCopyOfContextMap(), Map.of()));
    }

    /**
     * Calls this service's echo endpoint and returns what it received.
     *
     * @return the baggage header the called service received
     */
    @GetMapping("/downstream")
    Mono<String> downstream() {
      return selfClient
          .get()
          .uri("http://localhost:" + server.getWebServer().getPort() + "/echo-baggage")
          .retrieve()
          .bodyToMono(String.class);
    }

    /**
     * Returns the baggage header this request arrived with.
     *
     * @param baggage the W3C baggage header, if any
     * @return the header, or an empty string
     */
    @GetMapping("/echo-baggage")
    String echoBaggage(
        @RequestHeader(name = "baggage", required = false) @Nullable String baggage) {
      return Objects.requireNonNullElse(baggage, "");
    }
  }
}
