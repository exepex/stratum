package com.exepex.stratum.webflux.it;

import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebErrorCode;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
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
      "management.otlp.metrics.export.enabled=false",
      "spring.webflux.multipart.max-in-memory-size=1KB",
      "spring.webflux.multipart.max-disk-usage-per-part=2KB"
    })
@DisplayName("A reactive request whose body is larger than its limit is answered with 413")
class RequestBodyLimitIntegrationTest {

  /**
   * Whether the request declares its body's length: with {@code Content-Length}, then in chunks.
   */
  private static final List<Boolean> DECLARED_AND_STREAMED = List.of(true, false);

  /** The boundary between the parts of a multipart upload. */
  private static final String BOUNDARY = "stratum-upload";

  /** One kilobyte, the default limit. */
  private static final int KILOBYTE = 1024;

  /** A body over the 1KB default limit. */
  private static final int OVER_DEFAULT_LIMIT = 2 * KILOBYTE;

  /** A body inside the 4KB limit of the upload route. */
  private static final int WITHIN_ROUTE_LIMIT = 3 * KILOBYTE;

  /** A body over the 4KB limit of the upload route. */
  private static final int OVER_ROUTE_LIMIT = 5 * KILOBYTE;

  /** A body inside the 8KB limit of the import API's annotation. */
  private static final int WITHIN_ANNOTATION_LIMIT = 6 * KILOBYTE;

  /** The code a body over its limit is answered with. */
  private static final String TOO_LARGE = WebErrorCode.REQUEST_BODY_TOO_LARGE.getCode();

  @LocalServerPort private int port;

  @ParameterizedTest(name = "with Content-Length: {0}")
  @FieldSource("DECLARED_AND_STREAMED")
  @DisplayName("a body over the default limit gets SX-WEB-1008, declared or streamed")
  void defaultLimit(boolean declareLength) throws Exception {
    StarterTestSupport.checkBodyRefused(
        port, "/orders", MediaType.TEXT_PLAIN_VALUE, OVER_DEFAULT_LIMIT, declareLength, TOO_LARGE);
  }

  @ParameterizedTest(name = "with Content-Length: {0}")
  @FieldSource("DECLARED_AND_STREAMED")
  @DisplayName("a route's limit lets a larger upload in, and stops one over it")
  void routeLimit(boolean declareLength) throws Exception {
    StarterTestSupport.checkBodyAccepted(port, "/uploads/raw", WITHIN_ROUTE_LIMIT, declareLength);
    StarterTestSupport.checkBodyRefused(
        port,
        "/uploads/raw",
        MediaType.TEXT_PLAIN_VALUE,
        OVER_ROUTE_LIMIT,
        declareLength,
        TOO_LARGE);
  }

  @ParameterizedTest(name = "with Content-Length: {0}")
  @FieldSource("DECLARED_AND_STREAMED")
  @DisplayName("a method's @RequestBodyLimit wins over the default")
  void annotationWins(boolean declareLength) throws Exception {
    StarterTestSupport.checkBodyAccepted(
        port, "/orders/imports", WITHIN_ANNOTATION_LIMIT, declareLength);
  }

  @Test
  @DisplayName("a form over the limit, which Spring reads itself, is stopped by its length")
  void formOverLimit() throws Exception {
    StarterTestSupport.checkBodyRefused(
        port,
        "/forms",
        MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        OVER_DEFAULT_LIMIT,
        true,
        TOO_LARGE);
  }

  @Test
  @DisplayName("a chunked multipart upload over Spring's disk limit gets SX-WEB-1008")
  void multipartOverDiskLimit() throws Exception {
    var multipart =
        ("--"
                + BOUNDARY
                + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"orders.csv\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "x".repeat(OVER_ROUTE_LIMIT)
                + "\r\n--"
                + BOUNDARY
                + "--\r\n")
            .getBytes(StandardCharsets.US_ASCII);
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/files"))
            .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + BOUNDARY)
            .POST(
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(multipart)))
            .build();

    try (var client = HttpClient.newHttpClient()) {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());

      StarterTestSupport.checkProblemDetails(
          response, HttpStatus.CONTENT_TOO_LARGE.value(), TOO_LARGE);
    }
  }

  @Test
  @DisplayName("a buffer limit that is not the request body's, such as a downstream one, keeps 500")
  void otherBufferLimitKeeps500() throws Exception {
    var response = StarterTestSupport.getFromService(port, "/reports");

    StarterTestSupport.checkProblemDetails(
        response, HttpStatus.INTERNAL_SERVER_ERROR.value(), WebErrorCode.INTERNAL_ERROR.getCode());
  }
}
