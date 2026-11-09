package com.exepex.stratum.httpclient;

import com.exepex.stratum.test.StarterTestSupport;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The HTTP client library follows Stratum's architecture rules")
class HttpClientArchTest {

  /**
   * The library's types that services may use: the annotation of a client, and the settings record,
   * its builder and its customizer, as every module's. Every other type carries the label.
   */
  private static final Set<Class<?>> API_TYPES =
      Set.of(
          StratumClient.class,
          HttpClientProperties.class,
          HttpClientProperties.HttpClientPropertiesBuilder.class,
          ClientSettings.class,
          ClientSettings.ClientSettingsBuilder.class,
          HttpClientPropertiesCustomizer.class);

  @Test
  @DisplayName("it is safe to scan, has no cycles and every public type is internal or listed")
  void followsLibraryArchitecture() {
    StarterTestSupport.checkLibraryArchitecture(HttpClientConstants.class, API_TYPES);
  }
}
