package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.test.OrderErrorCode;
import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.testsupport.EdgeServiceWebSettings;
import com.exepex.stratum.web.testsupport.GoneOrderWebSettings;
import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("WebProperties come from properties, changed by Java configuration, which wins")
class WebPropertiesTest {

  /** The code of a duplicate order. */
  private static final String DUPLICATE_ORDER = OrderErrorCode.DUPLICATE_ORDER.getCode();

  /** The key of the status of a duplicate order in a {@code .properties} file. */
  private static final String DUPLICATE_ORDER_STATUS =
      WebConstants.ERROR_STATUSES + "." + DUPLICATE_ORDER;

  @Test
  @DisplayName("with nothing set, no code has a status")
  void defaultsToNoStatuses() {
    assertThat(WebProperties.resolve(new MockEnvironment()).error().statuses()).isEmpty();
  }

  @Test
  @DisplayName("statuses are read from a .properties file, with the code as written")
  void statusesFromProperties() {
    var environment = new MockEnvironment().withProperty(DUPLICATE_ORDER_STATUS, "409");

    assertThat(WebProperties.resolve(environment).error().statuses())
        .isEqualTo(Map.of(DUPLICATE_ORDER, HttpStatus.CONFLICT.value()));
  }

  @Test
  @DisplayName("statuses are read from a .yml file, with the code as written")
  void statusesFromYaml() throws IOException {
    var environment = new StandardEnvironment();
    new YamlPropertySourceLoader()
        .load("web-yaml", new ClassPathResource("web-yaml.yml"))
        .forEach(environment.getPropertySources()::addFirst);

    assertThat(WebProperties.resolve(environment).error().statuses())
        .isEqualTo(
            Map.of(
                "ORD-0001",
                HttpStatus.CONFLICT.value(),
                OrderErrorCode.STORE_DOWN.getCode(),
                HttpStatus.SERVICE_UNAVAILABLE.value()));
  }

  @Test
  @DisplayName("a customizer listed in spring.factories wins over the files")
  void javaConfigurationWins(@TempDir Path folder) throws IOException {
    var environment = new MockEnvironment().withProperty(DUPLICATE_ORDER_STATUS, "409");

    var properties =
        StarterTestSupport.withCustomizers(
            folder,
            WebPropertiesCustomizer.class,
            List.of(GoneOrderWebSettings.class),
            () -> WebProperties.resolve(environment));

    assertThat(properties.error().statuses())
        .isEqualTo(Map.of(DUPLICATE_ORDER, HttpStatus.GONE.value()));
  }

  @Test
  @DisplayName("a status that is not an error stops startup with SX-CORE-1003, without the code")
  void nonErrorStatusIsRejected() {
    var environment = new MockEnvironment().withProperty(DUPLICATE_ORDER_STATUS, "200");

    assertThatExceptionOfType(ConstraintViolationException.class)
        .isThrownBy(() -> WebProperties.resolve(environment))
        .withMessageStartingWith("SX-CORE-1003: Invalid settings: " + WebConstants.ERROR_STATUSES)
        .withMessageNotContaining(DUPLICATE_ORDER);
  }

  @Nested
  @DisplayName("the request field and correlation settings")
  class RequestFieldSettings {

    /** How many parsed user agents are kept by default. */
    private static final int DEFAULT_CACHE_SIZE = 10_000;

    /** How long a field may be by default. */
    private static final int DEFAULT_MAX_VALUE_LENGTH = 512;

    /** The cache size in web-yaml.yml. */
    private static final int YAML_CACHE_SIZE = 500;

    /** The field length the files set. */
    private static final int FILE_MAX_VALUE_LENGTH = 256;

    @Test
    @DisplayName("with nothing set, the defaults are safe and cheap")
    void defaults() {
      var settings = WebProperties.resolve(new MockEnvironment());

      assertThat(settings.correlation().acceptFromCaller()).isTrue();
      assertThat(settings.clientIp().trustedProxies())
          .containsExactly(
              "10.0.0.0/8",
              "172.16.0.0/12",
              "192.168.0.0/16",
              "127.0.0.0/8",
              "::1/128",
              "fc00::/7");
      assertThat(settings.userAgent())
          .isEqualTo(new WebProperties.UserAgentSettings(false, DEFAULT_CACHE_SIZE, false));
      assertThat(settings.clientLocation().database()).isNull();
      assertThat(settings.requestFields().maxValueLength()).isEqualTo(DEFAULT_MAX_VALUE_LENGTH);
    }

    @Test
    @DisplayName("are read from a .properties file")
    void fromProperties() {
      var environment =
          new MockEnvironment()
              .withProperty(WebConstants.CORRELATION_ACCEPT_FROM_CALLER, "false")
              .withProperty(WebConstants.CLIENT_IP_TRUSTED_PROXIES, "10.20.0.0/16,fd00::/8")
              .withProperty(WebConstants.USER_AGENT_PARSE, "true")
              .withProperty(WebConstants.CLIENT_LOCATION_DATABASE, "/data/city.mmdb")
              .withProperty(WebConstants.REQUEST_FIELDS_MAX_VALUE_LENGTH, "256");

      var settings = WebProperties.resolve(environment);

      assertThat(settings.correlation().acceptFromCaller()).isFalse();
      assertThat(settings.clientIp().trustedProxies()).containsExactly("10.20.0.0/16", "fd00::/8");
      assertThat(settings.userAgent().parse()).isTrue();
      assertThat(settings.clientLocation().database()).isEqualTo(Path.of("/data/city.mmdb"));
      assertThat(settings.requestFields().maxValueLength()).isEqualTo(FILE_MAX_VALUE_LENGTH);
    }

    @Test
    @DisplayName("are read from a .yml file")
    void fromYaml() throws IOException {
      var environment = new StandardEnvironment();
      new YamlPropertySourceLoader()
          .load("web-yaml", new ClassPathResource("web-yaml.yml"))
          .forEach(environment.getPropertySources()::addFirst);

      var settings = WebProperties.resolve(environment);

      assertThat(settings.correlation().acceptFromCaller()).isFalse();
      assertThat(settings.clientIp().trustedProxies()).containsExactly("10.20.0.0/16");
      assertThat(settings.userAgent())
          .isEqualTo(new WebProperties.UserAgentSettings(true, YAML_CACHE_SIZE, true));
      assertThat(settings.requestFields().maxValueLength()).isEqualTo(FILE_MAX_VALUE_LENGTH);
    }

    @Test
    @DisplayName("a customizer listed in spring.factories wins over the files")
    void javaConfigurationWins(@TempDir Path folder) throws IOException {
      var environment =
          new MockEnvironment().withProperty(WebConstants.CORRELATION_ACCEPT_FROM_CALLER, "true");

      var properties =
          StarterTestSupport.withCustomizers(
              folder,
              WebPropertiesCustomizer.class,
              List.of(EdgeServiceWebSettings.class),
              () -> WebProperties.resolve(environment));

      assertThat(properties.correlation().acceptFromCaller()).isFalse();
    }

    @Test
    @DisplayName("a field length out of range stops startup with SX-CORE-1003")
    void lengthOutOfRange() {
      var environment =
          new MockEnvironment().withProperty(WebConstants.REQUEST_FIELDS_MAX_VALUE_LENGTH, "8");

      assertThatExceptionOfType(ConstraintViolationException.class)
          .isThrownBy(() -> WebProperties.resolve(environment))
          .withMessageStartingWith(
              "SX-CORE-1003: Invalid settings: " + WebConstants.REQUEST_FIELDS_MAX_VALUE_LENGTH);
    }
  }

  @Nested
  @DisplayName("the request log settings")
  class RequestLogSettings {

    @Test
    @DisplayName("with nothing set, every request but the actuator's writes a line with no headers")
    void defaults() {
      assertThat(WebProperties.resolve(new MockEnvironment()).requestLog())
          .isEqualTo(
              new WebProperties.RequestLogSettings(
                  true,
                  List.of("/actuator/**"),
                  Map.of(),
                  Map.of(),
                  List.of(),
                  false,
                  List.of("sub", "azp", "client_id", "scope")));
    }

    @Test
    @DisplayName("are read from a .properties file, header names with their field names")
    void fromProperties() {
      var environment =
          new MockEnvironment()
              .withProperty(WebConstants.REQUEST_LOG_ENABLED, "false")
              .withProperty(WebConstants.REQUEST_LOG_SKIP_PATHS, "/actuator/**,/internal/**")
              .withProperty(WebConstants.REQUEST_LOG_REQUEST_HEADERS + ".X-Tenant-Id", "tenantId")
              .withProperty(WebConstants.REQUEST_LOG_RESPONSE_HEADERS + ".X-Cache", "cacheResult")
              .withProperty(WebConstants.REQUEST_LOG_DENIED_HEADERS, "X-Internal-Key")
              .withProperty(WebConstants.REQUEST_LOG_TOKEN_PAYLOAD, "true")
              .withProperty(WebConstants.REQUEST_LOG_TOKEN_CLAIMS, "sub,tenant");

      assertThat(WebProperties.resolve(environment).requestLog())
          .isEqualTo(
              new WebProperties.RequestLogSettings(
                  false,
                  List.of("/actuator/**", "/internal/**"),
                  Map.of("X-Tenant-Id", "tenantId"),
                  Map.of("X-Cache", "cacheResult"),
                  List.of("X-Internal-Key"),
                  true,
                  List.of("sub", "tenant")));
    }

    @Test
    @DisplayName("are read from a .yml file")
    void fromYaml() throws IOException {
      var environment = new StandardEnvironment();
      new YamlPropertySourceLoader()
          .load("web-yaml", new ClassPathResource("web-yaml.yml"))
          .forEach(environment.getPropertySources()::addFirst);

      var requestLog = WebProperties.resolve(environment).requestLog();

      assertThat(requestLog.requestHeaders()).containsExactly(Map.entry("X-Tenant-Id", "tenantId"));
      assertThat(requestLog.tokenPayload()).isTrue();
      assertThat(requestLog.tokenClaims()).containsExactly("sub", "tenant");
    }

    @Test
    @DisplayName("a field name a log platform cannot take stops startup with SX-CORE-1003")
    void badFieldName() {
      var environment =
          new MockEnvironment()
              .withProperty(WebConstants.REQUEST_LOG_REQUEST_HEADERS + ".X-Tenant-Id", "tenant id");

      assertThatExceptionOfType(ConstraintViolationException.class)
          .isThrownBy(() -> WebProperties.resolve(environment))
          .withMessageStartingWith(
              "SX-CORE-1003: Invalid settings: " + WebConstants.REQUEST_LOG_REQUEST_HEADERS);
    }
  }
}
