package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.catchThrowable;

import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.core.request.RequestTimeLeft;
import com.exepex.stratum.test.MetadataDefaultChecks;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("The data library's metadata lists the defaults the module uses")
class ConfigurationMetadataDefaultsTest {

  /** A web request's time left that a margin of 150 ms or more uses up. */
  private static final Duration MARGIN_USED_UP = Duration.ofMillis(150);

  /** A web request's time left that a margin of 150 ms or less leaves time in. */
  private static final Duration MARGIN_LEFT = Duration.ofMillis(151);

  /** The web request's time left, as the web module's bean reads it. */
  private Duration timeLeft = Duration.ZERO;

  @Test
  @DisplayName("each listed default is the value the module uses when its property is unset")
  void listsTheDefaultsInEffect() throws IOException {
    MetadataDefaultChecks.checkMetadataDefaults(this::inEffect);
  }

  /**
   * Returns the module switch, the cap on all pools' connections, the network timeout a borrow gets
   * from the pool's statement limit, and whether a borrow fails with 150 and with 151 ms left,
   * which shows the deadline margin.
   */
  private List<Object> inEffect(Map<String, String> properties) {
    var environment = new MockEnvironment();
    properties.forEach(environment::setProperty);
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton("requestTimeLeft", (RequestTimeLeft) () -> Optional.of(timeLeft));
    try (var factory =
        new StatementLimitsFactory(
            environment, beans, Thread.currentThread().getContextClassLoader())) {
      var limits = factory.forPool("dataSource", "main");
      return List.of(
          StratumModuleSwitch.isEnabled(environment, DataConstants.MODULE),
          DataProperties.resolve(environment).maxConnectionsOrStandard(),
          limits.borrowNetworkTimeout(),
          borrowFails(limits, MARGIN_USED_UP),
          borrowFails(limits, MARGIN_LEFT));
    }
  }

  /** Returns whether a borrow with the given time left fails. */
  private boolean borrowFails(StatementLimits limits, Duration left) {
    timeLeft = left;
    return catchThrowable(limits::checkBeforeBorrow) != null;
  }
}
