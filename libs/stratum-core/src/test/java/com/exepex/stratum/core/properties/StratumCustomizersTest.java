package com.exepex.stratum.core.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.core.properties.fixture.GreetingAutoConfiguration.GreetingProperties;
import com.exepex.stratum.core.properties.testsupport.GreetingTestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("StratumCustomizers resolves a module's settings before any bean exists")
class StratumCustomizersTest {

  /** The test module's prefix, as its settings type declares it. */
  private static final String GREETING_PREFIX = "stratum.greeting";

  @Test
  @DisplayName("the settings are bound from the environment, with defaults for anything unset")
  void bindsSettingsWithDefaults() {
    var environment = new MockEnvironment().withProperty("stratum.greeting.message", "Hi");

    assertThat(resolveGreetingSettings(environment))
        .isEqualTo(GreetingTestData.defaults().message("Hi").build());
  }

  @Test
  @DisplayName("a bad value stops startup")
  void rejectsBadValue() {
    var environment = new MockEnvironment().withProperty("stratum.greeting.message", " ");

    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(() -> resolveGreetingSettings(environment))
        .withMessageContaining("stratum.greeting.message");
  }

  /** Resolves the test module's settings, as a starter's {@code resolve} does. */
  private GreetingProperties resolveGreetingSettings(MockEnvironment environment) {
    return StratumCustomizers.resolveSettings(
        environment, GREETING_PREFIX, GreetingProperties.class, GreetingPropertiesCustomizer.class);
  }

  /** The test module's customizer interface; no class is listed for it in spring.factories. */
  @FunctionalInterface
  interface GreetingPropertiesCustomizer extends StratumPropertiesCustomizer<GreetingProperties> {}
}
