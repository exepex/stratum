package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.testsupport.EdgeServiceWebSettings;
import com.exepex.stratum.web.testsupport.WebTestData;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;

@DisplayName("The time and body size limits in WebProperties, with defaults that follow the mode")
class WebLimitSettingsTest {

  /** The limit of a request when nothing is set and backward-compatible is off. */
  private static final Duration STANDARD_TIMEOUT = Duration.ofSeconds(10);

  /** The body limit when nothing is set and backward-compatible is off. */
  private static final DataSize STANDARD_BODY_LIMIT = DataSize.parse("256KB");

  @Nested
  @DisplayName("the timeout settings")
  class TimeoutSettings {

    /** The default limit the files set. */
    private static final Duration FILE_LIMIT = Duration.ofSeconds(5);

    /** The limit of the report paths that the files set. */
    private static final Map<String, Duration> REPORT_ROUTES =
        Map.of("/reports/**", Duration.ofSeconds(60));

    @Test
    @DisplayName("with nothing set, no limit while backward-compatible, else 10 seconds; 503")
    void defaults() {
      var settings = WebProperties.resolve(new MockEnvironment());
      var standard = settings.toBuilder().backwardCompatible(false).build();
      var teamSet =
          WebProperties.resolve(
              new MockEnvironment().withProperty(WebConstants.TIMEOUT_DEFAULT_LIMIT, "5s"));

      assertThat(settings.defaultTimeout()).isZero();
      assertThat(standard.defaultTimeout()).isEqualTo(STANDARD_TIMEOUT);
      assertThat(teamSet.defaultTimeout()).isEqualTo(FILE_LIMIT);
      assertThat(settings.timeout().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    }

    @Test
    @DisplayName("are read from a .properties file, with the routes as path patterns")
    void fromProperties() {
      var environment =
          new MockEnvironment()
              .withProperty(WebConstants.TIMEOUT_DEFAULT_LIMIT, "5s")
              .withProperty(WebConstants.TIMEOUT_ROUTES + "[/reports/**]", "60s")
              .withProperty(WebConstants.TIMEOUT_STATUS, "504");

      assertThat(WebProperties.resolve(environment).timeout())
          .isEqualTo(
              new WebProperties.TimeoutSettings(
                  FILE_LIMIT, REPORT_ROUTES, HttpStatus.GATEWAY_TIMEOUT.value()));
    }

    @Test
    @DisplayName("are read from a .yml file")
    void fromYaml() throws IOException {
      var environment = WebTestData.yamlEnvironment();

      assertThat(WebProperties.resolve(environment).timeout())
          .isEqualTo(
              new WebProperties.TimeoutSettings(
                  FILE_LIMIT, REPORT_ROUTES, HttpStatus.GATEWAY_TIMEOUT.value()));
    }

    @Test
    @DisplayName(WebTestData.JAVA_CONFIGURATION_WINS)
    void javaConfigurationWins(@TempDir Path folder) throws IOException {
      var environment =
          new MockEnvironment().withProperty(WebConstants.TIMEOUT_DEFAULT_LIMIT, "5s");

      var properties =
          StarterTestSupport.withCustomizers(
              folder,
              WebPropertiesCustomizer.class,
              List.of(EdgeServiceWebSettings.class),
              () -> WebProperties.resolve(environment));

      assertThat(properties.timeout().defaultLimit()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("a status that is not an error stops startup with SX-CORE-1003")
    void nonErrorStatusIsRejected() {
      var environment = new MockEnvironment().withProperty(WebConstants.TIMEOUT_STATUS, "200");

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> WebProperties.resolve(environment))
          .withMessageStartingWith(WebTestData.INVALID_SETTINGS + WebConstants.TIMEOUT_STATUS);
    }
  }

  @Nested
  @DisplayName("the body limit settings")
  class BodyLimitSettings {

    /** The body limit the files set. */
    private static final DataSize FILE_LIMIT = DataSize.ofKilobytes(64);

    /** The limit of the upload paths that the files set. */
    private static final Map<String, DataSize> UPLOAD_ROUTES =
        Map.of("/uploads/**", DataSize.ofMegabytes(20));

    @Test
    @DisplayName("with nothing set, no limit while backward-compatible, else 256KB")
    void defaults() {
      var settings = WebProperties.resolve(new MockEnvironment());
      var standard = settings.toBuilder().backwardCompatible(false).build();
      var teamSet =
          WebProperties.resolve(
              new MockEnvironment().withProperty(WebConstants.BODY_LIMIT_DEFAULT_LIMIT, "64KB"));

      assertThat(settings.defaultBodyLimit()).isEqualTo(DataSize.ofBytes(0));
      assertThat(standard.defaultBodyLimit()).isEqualTo(STANDARD_BODY_LIMIT);
      assertThat(teamSet.defaultBodyLimit()).isEqualTo(FILE_LIMIT);
    }

    @Test
    @DisplayName("are read from a .properties file and from a .yml file")
    void fromFiles() throws IOException {
      var properties =
          new MockEnvironment()
              .withProperty(WebConstants.BODY_LIMIT_DEFAULT_LIMIT, "64KB")
              .withProperty(WebConstants.BODY_LIMIT_ROUTES + "[/uploads/**]", "20MB");
      var expected = new WebProperties.BodyLimitSettings(FILE_LIMIT, UPLOAD_ROUTES);

      assertThat(WebProperties.resolve(properties).bodyLimit()).isEqualTo(expected);
      assertThat(WebProperties.resolve(WebTestData.yamlEnvironment()).bodyLimit())
          .isEqualTo(expected);
    }

    @Test
    @DisplayName(WebTestData.JAVA_CONFIGURATION_WINS)
    void javaConfigurationWins(@TempDir Path folder) throws IOException {
      var environment =
          new MockEnvironment().withProperty(WebConstants.BODY_LIMIT_DEFAULT_LIMIT, "64KB");

      var properties =
          StarterTestSupport.withCustomizers(
              folder,
              WebPropertiesCustomizer.class,
              List.of(EdgeServiceWebSettings.class),
              () -> WebProperties.resolve(environment));

      assertThat(properties.bodyLimit().defaultLimit()).isEqualTo(DataSize.parse("16KB"));
    }
  }
}
