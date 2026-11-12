package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.httpclient.testsupport.HttpClientTestData;
import com.exepex.stratum.httpclient.testsupport.SettingsChecks;
import com.exepex.stratum.httpclient.testsupport.ThirdPartyInventorySettings;
import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("trace-headers comes from properties, YAML or Java, under defaults and each client")
class TraceHeadersSettingsTest {

  /** The key of the inventory client's trace headers. */
  private static final String INVENTORY_TRACE_HEADERS =
      HttpClientTestData.INVENTORY_SETTINGS + ".trace-headers";

  @Test
  @DisplayName("with nothing set, a client sends its trace headers")
  void defaultsToTrue() {
    assertThat(
            HttpClientProperties.resolve(new MockEnvironment())
                .clientSettings(HttpClientTestData.INVENTORY)
                .traceHeaders())
        .isTrue();
  }

  @Test
  @DisplayName("from properties, a client's own value wins over the shared default")
  void clientValueWinsInProperties() {
    var properties =
        HttpClientProperties.resolve(
            SettingsChecks.environmentOf(
                HttpClientConstants.DEFAULTS + ".trace-headers=false",
                INVENTORY_TRACE_HEADERS + "=true"));

    assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).traceHeaders()).isTrue();
    assertThat(properties.clientSettings(HttpClientTestData.PARTNER).traceHeaders()).isFalse();
  }

  @Test
  @DisplayName("from a .yml file, a client's own value wins over the shared default")
  void clientValueWinsInYaml() throws IOException {
    var properties = HttpClientProperties.resolve(HttpClientTestData.yamlEnvironment());

    assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).traceHeaders()).isTrue();
    assertThat(properties.clientSettings(HttpClientTestData.PARTNER).traceHeaders()).isFalse();
  }

  @Test
  @DisplayName(HttpClientTestData.JAVA_CONFIGURATION_WINS)
  void javaWinsOverFiles(@TempDir Path folder) throws IOException {
    var environment = SettingsChecks.environmentOf(INVENTORY_TRACE_HEADERS + "=true");

    var properties =
        StarterTestSupport.withCustomizers(
            folder,
            HttpClientPropertiesCustomizer.class,
            List.of(ThirdPartyInventorySettings.class),
            () -> HttpClientProperties.resolve(environment));

    assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).traceHeaders()).isFalse();
  }

  @Test
  @DisplayName("a value that is not true or false stops startup naming the key, not the value")
  void unknownValueStopsStartup() {
    var environment = SettingsChecks.environmentOf(INVENTORY_TRACE_HEADERS + "=sometimes");

    assertThatExceptionOfType(BindException.class)
        .isThrownBy(() -> HttpClientProperties.resolve(environment))
        .withMessageContaining("'" + INVENTORY_TRACE_HEADERS + "'")
        .withMessageNotContaining("sometimes");
  }
}
