package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.testsupport.HttpClientTestData;
import com.exepex.stratum.httpclient.testsupport.MockedInventorySettings;
import com.exepex.stratum.httpclient.testsupport.SlowInventorySettings;
import com.exepex.stratum.httpclient.testsupport.VendorKeySettings;
import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("HttpClientPropertiesCustomizer beans change the clients' settings and win over files")
class HttpClientPropertiesCustomizerTest {

  @Test
  @DisplayName(HttpClientTestData.JAVA_CONFIGURATION_WINS)
  void javaWinsOverFiles(@TempDir Path folder) throws IOException {
    var environment =
        new MockEnvironment()
            .withProperty(
                HttpClientTestData.INVENTORY_SETTINGS + ".url", HttpClientTestData.INVENTORY_URL)
            .withProperty(HttpClientTestData.INVENTORY_SETTINGS + ".timeout", "1s");

    var properties =
        StarterTestSupport.withCustomizers(
            folder,
            HttpClientPropertiesCustomizer.class,
            List.of(SlowInventorySettings.class),
            () -> HttpClientProperties.resolve(environment));

    var settings = properties.clientSettings(HttpClientTestData.INVENTORY);
    assertThat(settings.timeout()).isEqualTo(HttpClientTestData.REPORT_TIMEOUT);
    assertThat(settings.totalTimeout()).isEqualTo(HttpClientTestData.REPORT_TOTAL_TIMEOUT);
    assertThat(settings.url()).isEqualTo(HttpClientTestData.INVENTORY_URL);
  }

  @Test
  @DisplayName("a customizer sets a client's auth and SSL bundle, and wins over the files")
  void javaSetsAuth(@TempDir Path folder) throws IOException {
    var environment =
        new MockEnvironment()
            .withProperty(HttpClientTestData.INVENTORY_AUTH + ".bearer", HttpClientTestData.SECRET);

    var properties =
        StarterTestSupport.withCustomizers(
            folder,
            HttpClientPropertiesCustomizer.class,
            List.of(VendorKeySettings.class),
            () -> HttpClientProperties.resolve(environment));

    var settings = properties.clientSettings(HttpClientTestData.INVENTORY);
    assertThat(settings.auth()).isEqualTo(VendorKeySettings.AUTH);
    assertThat(settings.sslBundle()).isEqualTo(VendorKeySettings.SSL_BUNDLE);
  }

  @Test
  @DisplayName("a customizer turns a client's mock on, and wins over the files")
  void javaSetsMock(@TempDir Path folder) throws IOException {
    var environment =
        new MockEnvironment()
            .withProperty(HttpClientTestData.INVENTORY_SETTINGS + ".mock", "false");

    var properties =
        StarterTestSupport.withCustomizers(
            folder,
            HttpClientPropertiesCustomizer.class,
            List.of(MockedInventorySettings.class),
            () -> HttpClientProperties.resolve(environment));

    assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).mock()).isTrue();
  }
}
