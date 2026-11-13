package com.exepex.stratum.data.it;

import static com.exepex.stratum.test.DatabaseFailureAssertions.CONSTRAINT;
import static com.exepex.stratum.test.DatabaseFailureAssertions.PERSONAL_VALUE;
import static com.exepex.stratum.test.DatabaseFailureAssertions.chainOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.exepex.stratum.data.it.migration.MigrationApplication;
import com.exepex.stratum.test.DatabaseFailureAssertions;
import com.exepex.stratum.test.PostgresJdbcContainerConfiguration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseConnectionDetails;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName(
    "Flyway and Liquibase stop startup with no data value in the error, on every connection")
class MigrationBoundaryIntegrationTest {

  @Nested
  @DisplayName("when a migration breaks a unique key")
  class MigrationFailure {
    /**
     * The run arguments of each migration set-up. Run arguments, because the test's
     * application.properties turns both tools off and outranks the builder's properties. The user
     * is the container's own, {@code test}, so Spring Boot builds the tool its own connection from
     * the pool's URL.
     */
    private static final List<Arguments> MIGRATIONS =
        List.of(
            argumentSet(
                "Flyway on the data source bean",
                (Object)
                    new String[] {
                      "--spring.flyway.enabled=true",
                      "--spring.flyway.locations=classpath:migration/flyway"
                    }),
            argumentSet(
                "Flyway with spring.flyway.user, on its own connection",
                (Object)
                    new String[] {
                      "--spring.flyway.enabled=true",
                      "--spring.flyway.locations=classpath:migration/flyway",
                      "--spring.flyway.user=test"
                    }),
            argumentSet(
                "Liquibase with spring.liquibase.user, on its own connection",
                (Object)
                    new String[] {
                      "--spring.liquibase.enabled=true",
                      "--spring.liquibase.change-log=classpath:migration/liquibase/changelog.sql",
                      "--spring.liquibase.user=test"
                    }));

    @ParameterizedTest
    @FieldSource("MIGRATIONS")
    @DisplayName("The failure names the constraint and carries the duplicate address nowhere")
    void migrationFailureIsClean(String[] runArguments, CapturedOutput output) {
      var application =
          new SpringApplicationBuilder(
                  MigrationApplication.class, PostgresJdbcContainerConfiguration.class)
              .web(WebApplicationType.NONE);

      var failure = catchThrowable(() -> application.run(runArguments));

      assertThat(chainOf(failure).map(Throwable::getMessage))
          .anySatisfy(message -> assertThat(message).contains(CONSTRAINT));
      DatabaseFailureAssertions.assertServiceSeesNo(failure, output, PERSONAL_VALUE);
    }
  }

  @Nested
  @DisplayName("with the test container")
  class TestContainer {

    @Test
    @DisplayName(
        "gives Flyway and Liquibase no connection of their own, so the migration cases take the"
            + " data source bean and spring.flyway.user or spring.liquibase.user")
    void containerGivesMigrationToolsNoConnection() {
      try (var context =
          new SpringApplicationBuilder(
                  MigrationApplication.class, PostgresJdbcContainerConfiguration.class)
              .web(WebApplicationType.NONE)
              .run()) {
        assertThat(context.getBeanNamesForType(FlywayConnectionDetails.class)).isEmpty();
        assertThat(context.getBeanNamesForType(LiquibaseConnectionDetails.class)).isEmpty();
      }
    }
  }
}
