package com.exepex.stratum.data.autoconfigure;

import static com.exepex.stratum.data.testsupport.NamedPoolContexts.poolOf;
import static com.exepex.stratum.data.testsupport.NamedPoolContexts.withMainPool;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.condition.StratumModuleConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Spring Boot's pool is named main unless the service names it")
class MainPoolNameConfigurationTest {

  /** The name a service gives its pool. */
  private static final String ORDERS = "orders";

  @Test
  @DisplayName("a pool with no name is named main")
  void namesMain() {
    withMainPool()
        .run(context -> assertThat(poolOf(context, "dataSource").getPoolName()).isEqualTo("main"));
  }

  @Test
  @DisplayName("with stratum.backward-compatible=true too, since meters are internal")
  void namesMainInCompatibleMode() {
    withMainPool()
        .withPropertyValues(StratumModuleConstants.BACKWARD_COMPATIBLE + "=true")
        .run(context -> assertThat(poolOf(context, "dataSource").getPoolName()).isEqualTo("main"));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"spring.datasource.name", "spring.datasource.hikari.pool-name"})
  @DisplayName("a name the service set is kept")
  void keepsServiceName(String key) {
    withMainPool()
        .withPropertyValues(key + "=" + ORDERS)
        .run(context -> assertThat(poolOf(context, "dataSource").getPoolName()).isEqualTo(ORDERS));
  }
}
