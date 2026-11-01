package com.exepex.stratum.webmvc.it;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.test.ExistingOrderException;
import com.exepex.stratum.test.OrderErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
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
import org.springframework.web.filter.OncePerRequestFilter;

/** A service with the web MVC starter, whose endpoints fail in every way a real one can. */
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

  /** The endpoints. */
  @RestController
  static class OrdersController {

    /**
     * Fails with a code the service maps to 409.
     *
     * @return never returns
     */
    @GetMapping("/orders/duplicate")
    String duplicate() {
      throw new StratumException(OrderErrorCode.DUPLICATE_ORDER, "order 42");
    }

    /**
     * Fails with a code the service maps to 409, in an exception that implements {@code
     * FailureWithErrorCode} instead of extending {@code StratumException}.
     *
     * @return never returns
     */
    @GetMapping("/orders/duplicate-existing")
    String duplicateExisting() {
      throw new ExistingOrderException(OrderErrorCode.DUPLICATE_ORDER, "order 42");
    }

    /**
     * Fails with a code the service did not map.
     *
     * @return never returns
     */
    @GetMapping("/orders/unpriced")
    String unpriced() {
      throw new StratumException(OrderErrorCode.UNMAPPED);
    }

    /**
     * Places an order whose body is validated.
     *
     * @param order the order
     * @return what was placed
     */
    @PostMapping("/orders")
    String place(@Valid @RequestBody OrderRequest order) {
      return "placed " + order.quantity();
    }

    /**
     * Searches by a quantity that must be at least one.
     *
     * @param quantity how many items
     * @return what was found
     */
    @GetMapping("/orders/search")
    String search(@RequestParam @Min(1) int quantity) {
      return "found " + quantity;
    }

    /**
     * Finds an order by its numeric ID.
     *
     * @param orderId the order's ID
     * @return the order
     */
    @GetMapping("/orders/{orderId}")
    String find(@PathVariable long orderId) {
      return "order " + orderId;
    }

    /**
     * Fails with an exception annotated {@code @ResponseStatus}.
     *
     * @return never returns
     */
    @GetMapping("/orders/gone")
    String gone() {
      throw new OrderGoneException();
    }

    /**
     * Fails unexpectedly, with an internal detail in the message.
     *
     * @return never returns
     */
    @GetMapping("/orders/broken")
    String broken() {
      throw new IllegalStateException(INTERNAL_DETAIL);
    }

    /**
     * Fails with an exception the service answers itself, as Problem Details.
     *
     * @return never returns
     */
    @GetMapping("/orders/refund")
    String refund() {
      throw new RefundRefusedException();
    }

    /**
     * Fails with an exception the service answers itself, in its own shape.
     *
     * @return never returns
     */
    @GetMapping("/orders/legacy")
    String legacy() {
      throw new LegacyClientException();
    }

    /**
     * Answers 404 itself, without throwing, as some controllers do.
     *
     * @param response the response
     * @throws IOException if the error cannot be sent
     */
    @GetMapping("/orders/missing")
    void missing(HttpServletResponse response) throws IOException {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }
  }

  /**
   * A filter of the service that stops a request before it reaches a controller: it throws on one
   * path and rejects the caller with {@code 401}, as a security filter does, on another. It runs at
   * {@code -100}, the order of Spring Security's filter chain, so the test fails if Stratum's
   * request IDs filter ever runs after security.
   */
  @Order(-100)
  static class FailingFilter extends OncePerRequestFilter {

    /** The path on which it throws. */
    static final String FAILING_PATH = "/orders/filtered";

    /** The path on which it rejects the caller. */
    static final String REJECTING_PATH = "/orders/rejected";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
      return !FAILING_PATH.equals(request.getRequestURI())
          && !REJECTING_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws IOException {
      if (REJECTING_PATH.equals(request.getRequestURI())) {
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        return;
      }
      throw new IllegalStateException(INTERNAL_DETAIL);
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
