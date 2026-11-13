package com.exepex.stratum.datajpa.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataEnvironmentPostProcessor;
import com.exepex.stratum.data.DatabaseTlsCheck;
import com.exepex.stratum.data.FlywayBoundaryCustomizer;
import com.exepex.stratum.data.JdbcBoundaryPostProcessor;
import com.exepex.stratum.data.LiquibaseBoundaryPostProcessor;
import com.exepex.stratum.data.PostgresLoginTimeoutPostProcessor;
import com.exepex.stratum.jdbc.autoconfigure.JdbcStarterAutoConfiguration;
import java.util.List;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.FailureHandler;

@DisplayName("A service with both the data JPA and the JDBC starter gets each Stratum part once")
class BothDataStartersTest {

  /** The type of each bean that the two starters' auto-configurations both import. */
  private static final List<Class<?>> BOUNDARY_TYPES =
      List.of(
          JdbcBoundaryPostProcessor.class,
          PostgresLoginTimeoutPostProcessor.class,
          DatabaseTlsCheck.class,
          FlywayBoundaryCustomizer.class,
          LiquibaseBoundaryPostProcessor.class);

  /** A context with both starters' boundary auto-configurations. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  DataJpaBoundaryAutoConfiguration.class, JdbcStarterAutoConfiguration.class));

  @Test
  @DisplayName("each boundary bean is created once")
  void createsEachBeanOnce() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          BOUNDARY_TYPES.forEach(type -> assertThat(context).hasSingleBean(type));
        });
  }

  @Test
  @DisplayName("with the module off, none is created")
  void createsNothingWithModuleOff() {
    contextRunner
        .withPropertyValues(DataConstants.ENABLED + "=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              BOUNDARY_TYPES.forEach(type -> assertThat(context).doesNotHaveBean(type));
            });
  }

  @Test
  @DisplayName("the shared pool and transaction defaults run once, though both starters list them")
  void runsSharedDefaultsOnce() {
    var postProcessors =
        SpringFactoriesLoader.forDefaultResourceLocation()
            // Spring Boot's own post-processors need constructor arguments; they are skipped.
            .load(
                EnvironmentPostProcessor.class,
                null,
                FailureHandler.logging(LogFactory.getLog(getClass())))
            .stream()
            .filter(DataEnvironmentPostProcessor.class::isInstance)
            .toList();

    assertThat(postProcessors).hasSize(1);
  }
}
