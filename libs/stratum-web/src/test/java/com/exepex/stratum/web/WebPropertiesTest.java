package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.testsupport.GoneOrderWebSettings;
import com.exepex.stratum.web.testsupport.OrderErrorCode;
import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
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
}
