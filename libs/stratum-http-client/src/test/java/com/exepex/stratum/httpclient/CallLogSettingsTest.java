package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.httpclient.testsupport.HttpClientTestData;
import com.exepex.stratum.httpclient.testsupport.OnboardingInventorySettings;
import com.exepex.stratum.httpclient.testsupport.SettingsChecks;
import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("call-log comes from properties, YAML or Java, under defaults and each client")
class CallLogSettingsTest {

  /** The key of the inventory client's call log. */
  private static final String INVENTORY_CALL_LOG =
      HttpClientTestData.INVENTORY_SETTINGS + ".call-log";

  @Test
  @DisplayName("with nothing set, a client logs its failed calls")
  void defaultsToErrors() {
    assertThat(
            HttpClientProperties.resolve(new MockEnvironment())
                .clientSettings(HttpClientTestData.INVENTORY)
                .callLog())
        .isEqualTo(CallLog.ERRORS);
  }

  @Test
  @DisplayName("from properties, a client's own value wins over the shared default")
  void clientValueWinsInProperties() {
    var properties =
        HttpClientProperties.resolve(
            SettingsChecks.environmentOf(
                HttpClientConstants.DEFAULTS + ".call-log=none", INVENTORY_CALL_LOG + "=all"));

    assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).callLog())
        .isEqualTo(CallLog.ALL);
    assertThat(properties.clientSettings(HttpClientTestData.PARTNER).callLog())
        .isEqualTo(CallLog.NONE);
  }

  @Test
  @DisplayName("from a .yml file, a client's own value wins over the shared default")
  void clientValueWinsInYaml() throws IOException {
    var properties = HttpClientProperties.resolve(HttpClientTestData.yamlEnvironment());

    assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).callLog())
        .isEqualTo(CallLog.ALL);
    assertThat(properties.clientSettings(HttpClientTestData.PARTNER).callLog())
        .isEqualTo(CallLog.NONE);
  }

  @Test
  @DisplayName(HttpClientTestData.JAVA_CONFIGURATION_WINS)
  void javaWinsOverFiles(@TempDir Path folder) throws IOException {
    var environment = SettingsChecks.environmentOf(INVENTORY_CALL_LOG + "=none");

    var properties =
        StarterTestSupport.withCustomizers(
            folder,
            HttpClientPropertiesCustomizer.class,
            List.of(OnboardingInventorySettings.class),
            () -> HttpClientProperties.resolve(environment));

    assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).callLog())
        .isEqualTo(CallLog.ALL);
  }

  @Test
  @DisplayName("a value that is none of the three stops startup naming the key, not the value")
  void unknownValueStopsStartup() {
    var environment = SettingsChecks.environmentOf(INVENTORY_CALL_LOG + "=verbose");

    assertThatExceptionOfType(BindException.class)
        .isThrownBy(() -> HttpClientProperties.resolve(environment))
        .withMessageContaining("'" + INVENTORY_CALL_LOG + "'")
        .withMessageNotContaining("verbose");
  }
}
