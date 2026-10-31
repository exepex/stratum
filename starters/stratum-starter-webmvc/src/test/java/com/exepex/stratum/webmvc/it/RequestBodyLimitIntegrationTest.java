package com.exepex.stratum.webmvc.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

@SpringBootTest(
    classes = UploadApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "stratum.web.enabled=true",
      WebConstants.BODY_LIMIT_DEFAULT_LIMIT + "=1KB",
      WebConstants.BODY_LIMIT_ROUTES + "[/uploads/**]=4KB",
      "stratum.observability.enabled=true",
      "management.tracing.sampling.probability=1.0",
      "management.tracing.export.enabled=false",
      "management.otlp.metrics.export.enabled=false"
    })
@DisplayName("A Spring MVC request whose body is larger than its limit is answered with 413")
class RequestBodyLimitIntegrationTest {

  /** One kilobyte, the default limit. */
  private static final int KILOBYTE = 1024;

  /** A body inside the 4KB limit of the upload route. */
  private static final int WITHIN_ROUTE_LIMIT = 3 * KILOBYTE;

  /** A body over the 4KB limit of the upload route. */
  private static final int OVER_ROUTE_LIMIT = 5 * KILOBYTE;

  /** A body inside the 8KB limit of the import API's annotation. */
  private static final int WITHIN_ANNOTATION_LIMIT = 6 * KILOBYTE;

  @LocalServerPort private int port;

  @ParameterizedTest(name = "with Content-Length: {0}")
  @ValueSource(booleans = {true, false})
  @DisplayName("a body over the default limit gets SX-WEB-1008, declared or streamed")
  void defaultLimit(boolean declareLength) throws Exception {
    var response =
        StarterTestSupport.postToService(
            port, "/orders", MediaType.TEXT_PLAIN_VALUE, 2 * KILOBYTE, declareLength);

    StarterTestSupport.checkProblemDetails(
        response,
        HttpStatus.CONTENT_TOO_LARGE.value(),
        WebErrorCode.REQUEST_BODY_TOO_LARGE.getCode());
  }

  @ParameterizedTest(name = "with Content-Length: {0}")
  @ValueSource(booleans = {true, false})
  @DisplayName("a route's limit lets a larger upload in, and stops one over it")
  void routeLimit(boolean declareLength) throws Exception {
    var inLimit =
        StarterTestSupport.postToService(
            port, "/uploads/raw", MediaType.TEXT_PLAIN_VALUE, WITHIN_ROUTE_LIMIT, declareLength);
    var overLimit =
        StarterTestSupport.postToService(
            port, "/uploads/raw", MediaType.TEXT_PLAIN_VALUE, OVER_ROUTE_LIMIT, declareLength);

    assertThat(inLimit.statusCode()).isEqualTo(HttpStatus.OK.value());
    assertThat(inLimit.body()).isEqualTo(String.valueOf(WITHIN_ROUTE_LIMIT));
    StarterTestSupport.checkProblemDetails(
        overLimit,
        HttpStatus.CONTENT_TOO_LARGE.value(),
        WebErrorCode.REQUEST_BODY_TOO_LARGE.getCode());
  }

  @ParameterizedTest(name = "with Content-Length: {0}")
  @ValueSource(booleans = {true, false})
  @DisplayName("a method's @RequestBodyLimit wins over the default")
  void annotationWins(boolean declareLength) throws Exception {
    var response =
        StarterTestSupport.postToService(
            port,
            "/orders/imports",
            MediaType.TEXT_PLAIN_VALUE,
            WITHIN_ANNOTATION_LIMIT,
            declareLength);

    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
    assertThat(response.body()).isEqualTo(String.valueOf(WITHIN_ANNOTATION_LIMIT));
  }

  @Test
  @DisplayName("a form over the limit, which the server reads itself, is stopped by its length")
  void formOverLimit() throws Exception {
    var response =
        StarterTestSupport.postToService(
            port, "/forms", MediaType.APPLICATION_FORM_URLENCODED_VALUE, 2 * KILOBYTE, true);

    StarterTestSupport.checkProblemDetails(
        response,
        HttpStatus.CONTENT_TOO_LARGE.value(),
        WebErrorCode.REQUEST_BODY_TOO_LARGE.getCode());
  }
}
