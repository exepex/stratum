package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.StratumException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("StatementLimitsFactory gives each data source the pool's limit for the mode")
class StatementLimitsFactoryTest {

  /**
   * The mode, the service's {@code query-timeout} or {@code null} for unset, and the network
   * timeout a borrow gets: the pool's limit plus 30 seconds, none without a limit, and the largest
   * int for a limit too long to count.
   */
  private static final List<Arguments> POOL_LIMITS =
      List.of(
          Arguments.of(false, null, 60_000),
          Arguments.of(true, null, 0),
          Arguments.of(true, "10s", 40_000),
          Arguments.of(false, "0", 0),
          Arguments.of(false, "10m", 630_000),
          Arguments.of(false, "3650d", Integer.MAX_VALUE));

  /** Returns the network timeout a borrow gets, which is the pool's limit plus 30 seconds. */
  private static int borrowNetworkTimeoutWith(MockEnvironment environment) {
    try (var factory =
        new StatementLimitsFactory(
            environment, mock(BeanFactory.class), Thread.currentThread().getContextClassLoader())) {
      return factory.forPool("ordersDataSource").borrowNetworkTimeout();
    }
  }

  @ParameterizedTest(name = "backward-compatible={0}, query-timeout={1} gives {2} ms")
  @FieldSource("POOL_LIMITS")
  @DisplayName(
      "the pool's limit is 30s by default, none in backward-compatible mode, or the service's")
  void poolLimit(boolean backwardCompatible, @Nullable String queryTimeout, int networkTimeout) {
    var environment =
        new MockEnvironment()
            .withProperty("stratum.backward-compatible", String.valueOf(backwardCompatible));
    if (queryTimeout != null) {
      environment.setProperty(DataConstants.QUERY_TIMEOUT, queryTimeout);
    }

    assertThat(borrowNetworkTimeoutWith(environment)).isEqualTo(networkTimeout);
  }

  @Test
  @DisplayName("a negative limit stops startup with SX-CORE-1003")
  void negativeLimit() {
    var environment = new MockEnvironment().withProperty(DataConstants.QUERY_TIMEOUT, "-1s");

    var failure =
        catchThrowableOfType(StratumException.class, () -> borrowNetworkTimeoutWith(environment));

    assertThat(failure.getErrorCode()).isEqualTo(CoreErrorCode.INVALID_SETTINGS);
  }
}
