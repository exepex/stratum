package com.exepex.stratum.core.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("BackwardCompatibleMode reads the one compatibility switch, off unless set to true")
class BackwardCompatibleModeTest {

  /** The switch's key. */
  private static final String SWITCH = "stratum.backward-compatible";

  /** The web module's key, which the switch replaced and which is no longer read. */
  private static final String OLD_WEB_KEY = "stratum.web.backward-compatible";

  /** The service's settings, and whether the mode is on with them. */
  private static final List<Arguments> SETTINGS_AND_MODE =
      List.of(
          Arguments.of(Map.of(), false),
          Arguments.of(Map.of(SWITCH, "true"), true),
          Arguments.of(Map.of(SWITCH, "false"), false),
          Arguments.of(Map.of(OLD_WEB_KEY, "true"), false));

  @ParameterizedTest(name = "{0} gives {1}")
  @FieldSource("SETTINGS_AND_MODE")
  @DisplayName("is on only when stratum.backward-compatible is true")
  void isOnOnlyWhenSetToTrue(Map<String, String> settings, boolean expected) {
    var environment = new MockEnvironment();
    settings.forEach(environment::setProperty);

    assertThat(BackwardCompatibleMode.isOn(environment)).isEqualTo(expected);
  }
}
