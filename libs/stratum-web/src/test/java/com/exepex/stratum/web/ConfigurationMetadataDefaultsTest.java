package com.exepex.stratum.web;

import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.core.properties.BackwardCompatibleMode;
import com.exepex.stratum.test.MetadataDefaultChecks;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("The web library's metadata lists the defaults the module uses")
class ConfigurationMetadataDefaultsTest {

  @Test
  @DisplayName("each listed default is the value the module uses when its property is unset")
  void listsTheDefaultsInEffect() throws IOException {
    MetadataDefaultChecks.checkMetadataDefaults(this::inEffect);
  }

  /**
   * Returns the module switch and the settings the module uses, with the time and body limits that
   * apply to a request no route or handler limits.
   */
  private List<Object> inEffect(Map<String, String> properties) {
    var environment = new MockEnvironment();
    properties.forEach(environment::setProperty);
    var settings = WebProperties.resolve(environment);
    var backwardCompatible = BackwardCompatibleMode.isOn(environment);
    var bodyLimit = new RequestBodyLimits(settings, backwardCompatible).getDefaultLimit();
    try (var timeouts = new ResponseTimeouts(settings, backwardCompatible)) {
      var timeout = settings.timeout().toBuilder().defaultLimit(timeouts.getDefaultLimit());
      return List.of(
          StratumModuleSwitch.isEnabled(environment, WebConstants.MODULE),
          settings.toBuilder()
              .timeout(timeout.build())
              .bodyLimit(settings.bodyLimit().toBuilder().defaultLimit(bodyLimit).build())
              .build());
    }
  }
}
