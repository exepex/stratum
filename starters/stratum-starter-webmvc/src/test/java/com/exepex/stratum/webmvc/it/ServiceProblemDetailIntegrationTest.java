package com.exepex.stratum.webmvc.it;

import static com.exepex.stratum.test.WebTestSupport.checkServiceProblemDetail;
import static com.exepex.stratum.test.WebTestSupport.getFromService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import com.jayway.jsonpath.JsonPath;
import java.io.Serial;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@SpringBootTest(
    classes = ServiceProblemDetailIntegrationTest.RefundsApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.mvc.problemdetails.enabled=true", "stratum.observability.enabled=false"})
@DisplayName("A Spring MVC service's own Problem Details body gets the request path as instance")
class ServiceProblemDetailIntegrationTest {

  /** The path of a refund the service refuses with its own Problem Details body. */
  private static final String REFUND_PATH = "/refunds/7";

  /** A refund path whose encoded space must reach the body as it was sent. */
  private static final String ENCODED_REFUND_PATH = "/refunds/a%20b";

  /** The path whose handler sets an instance of its own. */
  private static final String RECEIPT_PATH = "/refunds/7/receipt";

  /** The instance the service's handler sets. */
  private static final String SERVICE_INSTANCE = "urn:refund:7";

  /** The path that fails with Spring's own exception, which Spring's own handler answers. */
  private static final String STATE_PATH = "/refunds/7/state";

  /** The context path a service can run under. */
  private static final String CONTEXT_PATH = "/shop";

  /** The port the service listens on. */
  @LocalServerPort private int port;

  @Nested
  @DisplayName("when the service's handler sets no instance")
  class NoInstance {

    @Test
    @DisplayName("the request path, without the query, becomes the instance")
    void pathWithoutQuery() throws Exception {
      var response =
          getFromService(port, REFUND_PATH + "?reason=late", ACCEPT, APPLICATION_JSON_VALUE);

      assertThat(response.statusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED.value());
      checkServiceProblemDetail(response, REFUND_PATH);
    }

    @Test
    @DisplayName("an encoded path is kept as it was sent")
    void encodedPathKept() throws Exception {
      var response = getFromService(port, ENCODED_REFUND_PATH, ACCEPT, APPLICATION_JSON_VALUE);

      assertThat(response.statusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED.value());
      checkServiceProblemDetail(response, ENCODED_REFUND_PATH);
    }
  }

  @Nested
  @DisplayName("when the service's handler sets its own instance")
  class OwnInstance {

    @Test
    @DisplayName("the service's instance is kept")
    void serviceInstanceKept() throws Exception {
      var response = getFromService(port, RECEIPT_PATH, ACCEPT, APPLICATION_JSON_VALUE);

      assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
      assertThat(JsonPath.<String>read(response.body(), "$.instance")).isEqualTo(SERVICE_INSTANCE);
    }
  }

  @Nested
  @DisplayName("when Spring's own handler answers, with spring.mvc.problemdetails.enabled")
  class SpringHandler {

    @Test
    @DisplayName("Spring's body gets the path, the IDs and no code")
    void springBodyGetsPath() throws Exception {
      var response = getFromService(port, STATE_PATH, ACCEPT, APPLICATION_JSON_VALUE);

      assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
      checkServiceProblemDetail(response, STATE_PATH);
    }
  }

  @Nested
  @TestPropertySource(properties = "server.servlet.context-path=" + CONTEXT_PATH)
  @DisplayName("when the service runs under a context path")
  class UnderContextPath {

    /** The port of the service under the context path. */
    @LocalServerPort private int contextPathPort;

    @Test
    @DisplayName("the instance starts with the context path, as the caller sent it")
    void contextPathKept() throws Exception {
      var response =
          getFromService(
              contextPathPort, CONTEXT_PATH + REFUND_PATH, ACCEPT, APPLICATION_JSON_VALUE);

      assertThat(response.statusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED.value());
      checkServiceProblemDetail(response, CONTEXT_PATH + REFUND_PATH);
    }
  }

  /** A service that answers its refund failures with its own Problem Details bodies. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({RefundsApplication.RefundsController.class, RefundsApplication.RefundHandlers.class})
  static class RefundsApplication {

    /** A refund the service refuses. */
    static class RefundRefusedException extends RuntimeException {

      @Serial private static final long serialVersionUID = 1L;
    }

    /** A receipt the service cannot find. */
    static class ReceiptMissingException extends RuntimeException {

      @Serial private static final long serialVersionUID = 1L;
    }

    /** The endpoints. */
    @RestController
    static class RefundsController {

      /**
       * Refuses every refund.
       *
       * @return never returns
       */
      @GetMapping("/refunds/{id}")
      String refund() {
        throw new RefundRefusedException();
      }

      /**
       * Finds no receipt.
       *
       * @return never returns
       */
      @GetMapping(RECEIPT_PATH)
      String receipt() {
        throw new ReceiptMissingException();
      }

      /**
       * Fails with Spring's own exception, which Spring's own handler answers.
       *
       * @return never returns
       */
      @GetMapping(STATE_PATH)
      String state() {
        throw new ErrorResponseException(HttpStatus.BAD_REQUEST);
      }
    }

    /** The service's handlers. */
    @RestControllerAdvice
    static class RefundHandlers {

      /**
       * Answers a refused refund with a body that has no instance.
       *
       * @return the body
       */
      @ExceptionHandler(RefundRefusedException.class)
      ProblemDetail refundRefused() {
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.PAYMENT_REQUIRED, "The refund was refused");
      }

      /**
       * Answers a missing receipt with a body that has an instance of its own.
       *
       * @return the body
       */
      @ExceptionHandler(ReceiptMissingException.class)
      ProblemDetail receiptMissing() {
        var body = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        body.setInstance(URI.create(SERVICE_INSTANCE));
        return body;
      }
    }
  }
}
