package com.exepex.stratum.webflux.it;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.webflux.testsupport.OrderErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.io.Serial;
import java.util.Map;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * A reactive service with the WebFlux starter, whose endpoints fail in every way a real one can.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({
  OrdersApplication.OrdersController.class,
  OrdersApplication.ServiceErrorHandlers.class,
  OrdersApplication.FailingFilter.class
})
public class OrdersApplication {

  /** An IBAN that a validation message would leak if it quoted the rejected value. */
  static final String IBAN = "DE89370400440532013000";

  /** An internal detail in an exception message, which must never reach a caller. */
  static final String INTERNAL_DETAIL = "LEAK-db-password-wrong";

  /**
   * An order as a caller sends it.
   *
   * @param quantity how many items; at least one
   * @param account the account to pay from; its message quotes the rejected value
   */
  record OrderRequest(
      @Min(1) int quantity,
      @Pattern(regexp = "[A-Z]{2}", message = "${validatedValue} is not a country")
          String account) {}

  /** An exception with a status of its own, as services declare them. */
  @ResponseStatus(HttpStatus.GONE)
  static class OrderGoneException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;
  }

  /** An exception the service answers with its own Problem Details body. */
  static class RefundRefusedException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;
  }

  /** An exception the service answers with a body of its own shape. */
  static class LegacyClientException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;
  }

  /** The endpoints, which fail inside the reactive pipeline, as reactive code does. */
  @RestController
  static class OrdersController {

    /**
     * Fails with a code the service maps to 409.
     *
     * @return never succeeds
     */
    @GetMapping("/orders/duplicate")
    Mono<String> duplicate() {
      return Mono.error(new StratumException(OrderErrorCode.DUPLICATE_ORDER, "order 42"));
    }

    /**
     * Fails with a code the service did not map.
     *
     * @return never succeeds
     */
    @GetMapping("/orders/unpriced")
    Mono<String> unpriced() {
      return Mono.error(new StratumException(OrderErrorCode.UNMAPPED));
    }

    /**
     * Places an order whose body is validated.
     *
     * @param order the order
     * @return what was placed
     */
    @PostMapping("/orders")
    Mono<String> place(@Valid @RequestBody OrderRequest order) {
      return Mono.just("placed " + order.quantity());
    }

    /**
     * Searches by a quantity that must be at least one.
     *
     * @param quantity how many items
     * @return what was found
     */
    @GetMapping("/orders/search")
    Mono<String> search(@RequestParam @Min(1) int quantity) {
      return Mono.just("found " + quantity);
    }

    /**
     * Finds an order by its numeric ID.
     *
     * @param orderId the order's ID
     * @return the order
     */
    @GetMapping("/orders/{orderId}")
    Mono<String> find(@PathVariable long orderId) {
      return Mono.just("order " + orderId);
    }

    /**
     * Fails with an exception annotated {@code @ResponseStatus}.
     *
     * @return never succeeds
     */
    @GetMapping("/orders/gone")
    Mono<String> gone() {
      return Mono.error(new OrderGoneException());
    }

    /**
     * Fails unexpectedly, with an internal detail in the message, thrown before any {@code Mono}.
     *
     * @return never returns
     */
    @GetMapping("/orders/broken")
    Mono<String> broken() {
      throw new IllegalStateException(INTERNAL_DETAIL);
    }

    /**
     * Fails with an exception the service answers itself, as Problem Details.
     *
     * @return never succeeds
     */
    @GetMapping("/orders/refund")
    Mono<String> refund() {
      return Mono.error(new RefundRefusedException());
    }

    /**
     * Fails with an exception the service answers itself, in its own shape.
     *
     * @return never succeeds
     */
    @GetMapping("/orders/legacy")
    Mono<String> legacy() {
      return Mono.error(new LegacyClientException());
    }
  }

  /**
   * A filter of the service that stops a request before it reaches a controller: it fails on one
   * path and rejects the caller with {@code 401}, as a security filter can, on another. It runs at
   * {@code -100}, the order of Spring Security's filter chain.
   */
  @Order(-100)
  static class FailingFilter implements WebFilter {

    /** The path on which it fails. */
    static final String FAILING_PATH = "/orders/filtered";

    /** The path on which it rejects the caller. */
    static final String REJECTING_PATH = "/orders/rejected";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
      return switch (exchange.getRequest().getPath().value()) {
        case FAILING_PATH -> Mono.error(new IllegalStateException(INTERNAL_DETAIL));
        case REJECTING_PATH -> Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        default -> chain.filter(exchange);
      };
    }
  }

  /** The service's own error handling, which Stratum must leave as it is. */
  @RestControllerAdvice
  static class ServiceErrorHandlers {

    /**
     * Answers a refused refund with the service's own Problem Details.
     *
     * @return the body
     */
    @ExceptionHandler(RefundRefusedException.class)
    ProblemDetail refundRefused() {
      return ProblemDetail.forStatusAndDetail(
          HttpStatus.PAYMENT_REQUIRED, "The refund was refused");
    }

    /**
     * Answers in the shape an old client expects.
     *
     * @return the body
     */
    @ExceptionHandler(LegacyClientException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    Map<String, String> legacyClient() {
      return Map.of("error", "legacy-format");
    }
  }
}
