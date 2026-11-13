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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
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

  @Nested
  @DisplayName("the boundary beans")
  class BoundaryBeans {

    @Test
    @DisplayName("are each created once")
    void createsEachBeanOnce() {
      contextRunner.run(context -> assertEachBeanOnce(context));
    }

    /** Checks that the context started with one bean of each boundary type. */
    private void assertEachBeanOnce(AssertableApplicationContext context) {
      assertThat(context).hasNotFailed();
      BOUNDARY_TYPES.forEach(type -> assertThat(context).hasSingleBean(type));
    }
  }

  @Nested
  @DisplayName("the module switch")
  class ModuleSwitch {

    @Test
    @DisplayName("set to false, turns every boundary bean off")
    void createsNothingWithModuleOff() {
      contextRunner
          .withPropertyValues(DataConstants.ENABLED + "=false")
          .run(context -> assertNoBoundaryBean(context));
    }

    /** Checks that the context started with no bean of any boundary type. */
    private void assertNoBoundaryBean(AssertableApplicationContext context) {
      assertThat(context).hasNotFailed();
      BOUNDARY_TYPES.forEach(type -> assertThat(context).doesNotHaveBean(type));
    }
  }

  @Nested
  @DisplayName("the shared pool and transaction defaults")
  class SharedDefaults {

    @Test
    @DisplayName("run once, though both starters list them")
    void runsSharedDefaultsOnce() {
      var postProcessors =
          SpringFactoriesLoader.forDefaultResourceLocation()
              // Spring Boot's own post-processors need constructor arguments; they are skipped.
              // The same loading as JdbcClassPathTest's, kept by the owner's decision.
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
}
