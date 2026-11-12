package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.exepex.stratum.httpclient.testsupport.HttpClientTestData;
import com.exepex.stratum.httpclient.testsupport.MergedInventorySettings;
import com.exepex.stratum.httpclient.testsupport.SettingsChecks;
import com.exepex.stratum.httpclient.testsupport.TenantHeaderSettings;
import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("A client's headers and copied headers merge with the defaults, and some are refused")
class HeaderSettingsTest {

  /** A header a partner asks for. */
  private static final String PARTNER_ID = "X-Partner-Id";

  /** The value of {@link #PARTNER_ID} in most tests. */
  private static final String ORDERS = "orders";

  /** A header a client copies or sends. */
  private static final String REGION = "X-Region";

  /** The header the defaults copy. */
  private static final String TENANT = "X-Tenant";

  /** A header the defaults send. */
  private static final String ORIGIN = "X-Origin";

  /** The value of {@link #ORIGIN} in the defaults. */
  private static final String SHOP = "shop";

  /** The shared copied headers. */
  private static final String DEFAULT_COPY_HEADERS = HttpClientConstants.DEFAULTS + ".copy-headers";

  /** The inventory client's copied headers. */
  private static final String INVENTORY_COPY_HEADERS =
      HttpClientTestData.INVENTORY_SETTINGS + ".copy-headers";

  /** The rule a refused header breaks. */
  private static final String HEADERS_ALLOWED = "headers-allowed";

  /** The prefix of the shared headers. */
  private static final String DEFAULT_HEADERS = HttpClientConstants.DEFAULTS + ".headers";

  /** The prefix of the inventory client's headers. */
  private static final String INVENTORY_HEADERS =
      HttpClientTestData.INVENTORY_SETTINGS + ".headers";

  @Nested
  @DisplayName("when a client's settings merge with the defaults")
  class Merge {

    @Test
    @DisplayName("for the same name, compared without case, the client's value wins, once")
    void clientWinsWithoutCase() {
      var merged =
          ClientSettings.builder()
              .headers(Map.of("x-partner-id", "billing"))
              .build()
              .orElse(ClientSettings.builder().headers(Map.of(PARTNER_ID, ORDERS)).build());

      assertThat(merged.headers()).containsExactly(Map.entry("x-partner-id", "billing"));
    }

    @Test
    @DisplayName("an empty value drops the default, also when merged again, and the others stay")
    void emptyValueDropsDefault() {
      var defaults =
          ClientSettings.builder().headers(Map.of(PARTNER_ID, ORDERS, ORIGIN, SHOP)).build();
      var merged =
          ClientSettings.builder().headers(Map.of(PARTNER_ID, "")).build().orElse(defaults);

      assertThat(merged.headers())
          .containsExactlyInAnyOrderEntriesOf(Map.of(PARTNER_ID, "", ORIGIN, SHOP));
      assertThat(merged.orElse(defaults).headers()).isEqualTo(merged.headers());
    }

    @Test
    @DisplayName("copied headers are joined, the defaults' first, each name once without case")
    void copyHeadersJoined() {
      var merged =
          ClientSettings.builder()
              .copyHeaders(List.of(REGION, "x-tenant", "X-REGION"))
              .build()
              .orElse(ClientSettings.builder().copyHeaders(List.of(TENANT)).build());

      assertThat(merged.copyHeaders()).containsExactly(TENANT, REGION);
    }

    @Test
    @DisplayName("with neither set, a client has no header and copies none")
    void noneByDefault() {
      var settings =
          HttpClientProperties.resolve(new MockEnvironment())
              .clientSettings(HttpClientTestData.INVENTORY);

      assertThat(settings.headers()).isEmpty();
      assertThat(settings.copyHeaders()).isEmpty();
    }
  }

  @Nested
  @DisplayName("a header that carries a user's identity or a credential stops startup")
  class Refused {

    /**
     * Settings that each name a refused header, in mixed case, in each key, under the defaults and
     * a client, and the client's API key header, also when it comes from the defaults.
     */
    private static final List<List<String>> REFUSED_SETTINGS =
        List.of(
            List.of(DEFAULT_HEADERS + ".[authorization]=" + HttpClientTestData.SECRET),
            List.of(INVENTORY_HEADERS + ".[COOKIE]=" + HttpClientTestData.SECRET),
            List.of(HttpClientConstants.DEFAULTS + ".copy-headers=Set-Cookie"),
            List.of(HttpClientTestData.INVENTORY_SETTINGS + ".copy-headers[0]=proxy-AUTHORIZATION"),
            List.of(
                HttpClientTestData.INVENTORY_AUTH + ".api-key=" + HttpClientTestData.SECRET,
                DEFAULT_HEADERS + ".[x-api-KEY]=" + HttpClientTestData.SECRET),
            List.of(
                HttpClientTestData.INVENTORY_AUTH + ".api-key=" + HttpClientTestData.SECRET,
                HttpClientTestData.INVENTORY_AUTH + ".api-key-header=X-Vendor-Key",
                HttpClientTestData.INVENTORY_SETTINGS + ".copy-headers[0]=X-VENDOR-key"));

    @ParameterizedTest(name = "{0}")
    @FieldSource("REFUSED_SETTINGS")
    @DisplayName("with a message that names only the rule, never the client, header or value")
    void refusedHeaderStopsStartup(List<String> settings) {
      SettingsChecks.assertSettingsInvalid(HEADERS_ALLOWED, settings.toArray(String[]::new));
    }

    @Test
    @DisplayName("an empty value that drops a default API key header sends nothing, so is allowed")
    void emptyDropOfKeyHeaderAllowed() {
      var environment =
          new MockEnvironment()
              .withProperty(DEFAULT_HEADERS + ".[X-Api-Key]", "shared")
              .withProperty(
                  HttpClientTestData.INVENTORY_AUTH + ".api-key", HttpClientTestData.SECRET)
              .withProperty(INVENTORY_HEADERS + ".[X-Api-Key]", "");

      assertThatCode(() -> HttpClientProperties.resolve(environment)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("another client's API key header is allowed as this client's own header")
    void otherClientsKeyHeaderAllowed() {
      var environment =
          new MockEnvironment()
              .withProperty(HttpClientTestData.PARTNER_AUTH + ".api-key", HttpClientTestData.SECRET)
              .withProperty(HttpClientTestData.PARTNER_AUTH + ".api-key-header", "X-Vendor-Key")
              .withProperty(INVENTORY_HEADERS + ".[X-Vendor-Key]", "inventory");

      assertThatCode(() -> HttpClientProperties.resolve(environment)).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("in the settings' text")
  class Text {

    @Test
    @DisplayName("a header's name is shown, never its value, also in the module's settings")
    void headerValueHidden() {
      var environment =
          new MockEnvironment().withProperty(INVENTORY_HEADERS + ".[From]", "priya@example.com");

      var properties = HttpClientProperties.resolve(environment);

      assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).toString())
          .contains("headers=[From]")
          .doesNotContain("priya@example.com");
      assertThat(properties.toString()).doesNotContain("priya@example.com");
    }

    @Test
    @DisplayName("every setting of a client is shown by its name")
    void everySettingShown() {
      var text =
          HttpClientProperties.resolve(new MockEnvironment())
              .clientSettings(HttpClientTestData.INVENTORY)
              .toString();

      assertThat(ClientSettings.class.getRecordComponents())
          .isNotEmpty()
          .allSatisfy(setting -> assertThat(text).contains(setting.getName() + "="));
    }
  }

  @Nested
  @DisplayName("from properties, YAML and Java")
  class Binding {

    @Test
    @DisplayName("a customizer that stores merged settings back keeps a dropped default dropped")
    void droppedDefaultSurvivesJava(@TempDir Path folder) throws IOException {
      var environment =
          new MockEnvironment()
              .withProperty(DEFAULT_HEADERS + ".[" + ORIGIN + "]", SHOP)
              .withProperty(INVENTORY_HEADERS + ".[" + ORIGIN + "]", "")
              .withProperty(DEFAULT_COPY_HEADERS, TENANT)
              .withProperty(INVENTORY_COPY_HEADERS, REGION);

      var properties =
          StarterTestSupport.withCustomizers(
              folder,
              HttpClientPropertiesCustomizer.class,
              List.of(MergedInventorySettings.class),
              () -> HttpClientProperties.resolve(environment));

      var settings = properties.clientSettings(HttpClientTestData.INVENTORY);
      assertThat(settings.timeout()).isEqualTo(HttpClientTestData.REPORT_TIMEOUT);
      assertThat(settings.headers()).doesNotContainEntry(ORIGIN, SHOP);
      assertThat(settings.copyHeaders()).containsExactly(TENANT, REGION);
    }

    @Test
    @DisplayName("a header name in brackets keeps its case, and a plain one is read too")
    void fromProperties() {
      var environment =
          new MockEnvironment()
              .withProperty(INVENTORY_HEADERS + ".[X-Partner-Id]", ORDERS)
              .withProperty(INVENTORY_HEADERS + "." + ORIGIN, SHOP)
              .withProperty(INVENTORY_COPY_HEADERS, "X-Tenant,X-Region");

      var settings =
          HttpClientProperties.resolve(environment).clientSettings(HttpClientTestData.INVENTORY);

      assertThat(settings.headers()).containsEntry(PARTNER_ID, ORDERS);
      assertThat(settings.headers().keySet()).anyMatch(ORIGIN::equalsIgnoreCase);
      assertThat(settings.copyHeaders()).containsExactly(TENANT, REGION);
    }

    @Test
    @DisplayName("headers and copied headers are read from a .yml file and merged")
    void fromYaml() throws IOException {
      var settings =
          HttpClientProperties.resolve(HttpClientTestData.yamlEnvironment())
              .clientSettings(HttpClientTestData.INVENTORY);

      assertThat(settings.headers())
          .containsExactlyInAnyOrderEntriesOf(Map.of(PARTNER_ID, ORDERS, REGION, "eu"));
      assertThat(settings.copyHeaders()).containsExactly(TENANT, "X-Request-Origin");
    }

    @Test
    @DisplayName(HttpClientTestData.JAVA_CONFIGURATION_WINS)
    void javaWinsOverFiles(@TempDir Path folder) throws IOException {
      var environment =
          new MockEnvironment()
              .withProperty(INVENTORY_HEADERS + ".[X-Partner-Id]", "file")
              .withProperty(INVENTORY_COPY_HEADERS, REGION);

      var properties =
          StarterTestSupport.withCustomizers(
              folder,
              HttpClientPropertiesCustomizer.class,
              List.of(TenantHeaderSettings.class),
              () -> HttpClientProperties.resolve(environment));

      var settings = properties.clientSettings(HttpClientTestData.INVENTORY);
      assertThat(settings.headers()).isEqualTo(TenantHeaderSettings.HEADERS);
      assertThat(settings.copyHeaders()).isEqualTo(TenantHeaderSettings.COPY_HEADERS);
    }
  }
}
