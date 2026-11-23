package com.exepex.stratum.webflux.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

@DisplayName("A reactive multipart upload gets Stratum's limits, not Spring Boot's, in both modes")
class MultipartLimitsIntegrationTest {

  /** A part larger than Spring Boot's 1MB default and smaller than Stratum's 10MB. */
  private static final int TWO_MEGABYTES = 2 * 1024 * 1024;

  /** One part more than Spring Boot's and Stratum's default of 50. */
  private static final int FIFTY_ONE_PARTS = 51;

  @Nested
  @SpringBootTest(
      classes = UploadApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {
        "stratum.backward-compatible=true",
        "management.tracing.export.otlp.enabled=false",
        "management.otlp.metrics.export.enabled=false"
      })
  @DisplayName("in backward-compatible mode, with no multipart limit")
  class BackwardCompatible {

    /** The application's port. */
    @LocalServerPort private int port;

    @Test
    @DisplayName("a 2MB part is accepted")
    void twoMegabytePart() throws Exception {
      var response = ChunkedUploads.postMultipartInChunks(port, 1, TWO_MEGABYTES);

      assertThat(response.statusCode()).as(response.body()).isEqualTo(HttpStatus.OK.value());
      assertThat(response.body()).isEqualTo("1");
    }

    @Test
    @DisplayName("51 parts are accepted")
    void fiftyOneParts() throws Exception {
      var response = ChunkedUploads.postMultipartInChunks(port, FIFTY_ONE_PARTS, 1);

      assertThat(response.statusCode()).as(response.body()).isEqualTo(HttpStatus.OK.value());
      assertThat(response.body()).isEqualTo(String.valueOf(FIFTY_ONE_PARTS));
    }
  }

  @Nested
  @SpringBootTest(
      classes = UploadApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {
        "stratum.backward-compatible=false",
        "management.tracing.export.otlp.enabled=false",
        "management.otlp.metrics.export.enabled=false"
      })
  @DisplayName("in strict mode")
  class Strict {

    /** The application's port. */
    @LocalServerPort private int port;

    @Test
    @DisplayName("a 2MB part is accepted under Stratum's 10MB, not refused at Spring Boot's 1MB")
    void twoMegabytePart() throws Exception {
      var response = ChunkedUploads.postMultipartInChunks(port, 1, TWO_MEGABYTES);

      assertThat(response.statusCode()).as(response.body()).isEqualTo(HttpStatus.OK.value());
    }
  }
}
