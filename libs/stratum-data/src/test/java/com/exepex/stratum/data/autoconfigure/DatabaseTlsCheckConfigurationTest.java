package com.exepex.stratum.data.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DatabaseTlsCheck;
import com.exepex.stratum.data.testsupport.DataStarterStandIns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("JdbcBoundaryConfiguration adds the check of each data source's encryption")
class DatabaseTlsCheckConfigurationTest {

  /** A context with the data module's boundary configuration. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(DataStarterStandIns.JdbcBoundaryStandIn.class));

  @Test
  @DisplayName("the check is a bean with the stable name stratumDatabaseTlsCheck")
  void addsCheck() {
    contextRunner.run(
        context ->
            assertThat(context)
                .hasBean(DataConstants.TLS_CHECK)
                .getBean(DataConstants.TLS_CHECK)
                .isInstanceOf(DatabaseTlsCheck.class));
  }

  @Test
  @DisplayName("a service's bean of the same name replaces the check")
  void serviceReplacesCheck() {
    contextRunner
        .withBean(DataConstants.TLS_CHECK, String.class, () -> "no check")
        .run(context -> assertThat(context).doesNotHaveBean(DatabaseTlsCheck.class));
  }

  @Test
  @DisplayName("with the module off, there is no check")
  void noCheckWithModuleOff() {
    contextRunner
        .withPropertyValues(DataConstants.ENABLED + "=false")
        .run(context -> assertThat(context).doesNotHaveBean(DatabaseTlsCheck.class));
  }
}
