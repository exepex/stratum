package com.exepex.stratum.web.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.web.WebProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// The web case is covered by each web starter's error configuration test, which has a web stack.
@DisplayName("WebSettingsConfiguration works out the web settings only in a web application")
class WebSettingsConfigurationTest {

  /** Switches the web module on, with a status the settings reject. */
  private static final String[] MODULE_ON_WITH_BAD_STATUS = {
    "stratum.web.enabled=true", "stratum.web.error.statuses.ORD-0001=99"
  };

  @Test
  @DisplayName(
      "an application that is not a web application, but scans Stratum, reads no web settings")
  void nonWebApplicationReadsNoWebSettings() {
    new ApplicationContextRunner()
        .withUserConfiguration(WebSettingsConfiguration.class)
        .withPropertyValues(MODULE_ON_WITH_BAD_STATUS)
        .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(WebProperties.class));
  }
}
