package com.exepex.stratum.data.it;

import static com.exepex.stratum.data.testsupport.DataAssertions.chainOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.it.boundary.BoundaryApplication;
import com.exepex.stratum.data.it.migration.MigrationApplication;
import com.exepex.stratum.data.testsupport.SilentDatabase;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.postgresql.Driver;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

/**
 * Starts services against a database that accepts connections and never answers, and against the
 * test database, without a {@code loginTimeout} of their own.
 */
@Slf4j
@DisplayName("On PostgreSQL, a database that never answers fails the connection within seconds")
class PostgresLoginTimeoutIntegrationTest {

  /** The longest a failure may take; the expected time is about 4 seconds, or 3 for a migration. */
  private static final Duration BOUND = Duration.ofSeconds(10);

  /**
   * How long a test against the silent database may run. On its own thread, since a read of a
   * socket that never answers cannot be interrupted: without the limit the test fails here instead
   * of hanging.
   */
  private static final int TEST_LIMIT_SECONDS = 60;

  /** How many borrows the pool test makes. */
  private static final int ATTEMPTS = 3;

  /** The nanoseconds in one microsecond, for printing medians. */
  private static final double NANOS_PER_MICRO = 1000.0;

  /** Returns the time since a start, read from {@link System#nanoTime()}. */
  private static Duration since(long startNanos) {
    return Duration.ofNanos(System.nanoTime() - startNanos);
  }

  @Nested
  @DisplayName("Spring Boot's pool")
  class Pool {

    @Test
    @Timeout(value = TEST_LIMIT_SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    @DisplayName(
        "fails each borrow within 10 s and leaves at most one driver thread alive per attempt,"
            + " holding the connection the database never answers")
    void failsEachBorrowInSeconds() throws IOException {
      try (var database = new SilentDatabase();
          var context =
              new SpringApplicationBuilder(BoundaryApplication.class)
                  .web(WebApplicationType.NONE)
                  // A bean of the check's name turns the TLS check off, which would borrow too.
                  .initializers(
                      app ->
                          app.getBeanFactory()
                              .registerSingleton(DataConstants.TLS_CHECK, new Object()))
                  .properties(startupProperties(database.url()))
                  .run()) {
        var dataSource = context.getBean(DataSource.class);
        long threadsBefore = SilentDatabase.driverThreads();

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
          long start = System.nanoTime();
          var failure = catchThrowable(dataSource::getConnection);
          var took = since(start);
          long threads = SilentDatabase.driverThreads() - threadsBefore;
          if (log.isInfoEnabled()) {
            log.info(
                "Borrow {} failed after {} ms; driver threads alive: {}",
                attempt,
                took.toMillis(),
                threads);
          }

          assertThat(failure).isInstanceOf(SQLException.class);
          assertThat(took).isLessThan(BOUND);
          assertThat(threads).isPositive().isLessThanOrEqualTo(attempt);
        }
      }
    }

    /** Returns the properties of a service whose only data source is the silent database. */
    private String[] startupProperties(String url) {
      return Stream.concat(
              Stream.of("spring.datasource.url=" + url), SilentDatabase.STARTUP_PROPERTIES.stream())
          .toArray(String[]::new);
    }
  }

  @Nested
  @DisplayName("the connection Spring Boot builds for Flyway or Liquibase")
  class Migration {

    /**
     * Each migration set-up, as run arguments, which outrank the test's application.properties;
     * {@code %s} stands for the silent database's URL. The data source points at it too, so no
     * other database is needed.
     */
    private static final List<Arguments> MIGRATIONS =
        List.of(
            argumentSet(
                "Flyway with spring.flyway.url",
                (Object)
                    new String[] {
                      "--spring.flyway.enabled=true",
                      "--spring.flyway.url=%s",
                      "--spring.flyway.user=test"
                    }),
            argumentSet(
                "Flyway with only spring.flyway.user, derived from the data source",
                (Object)
                    new String[] {"--spring.flyway.enabled=true", "--spring.flyway.user=test"}),
            argumentSet(
                "Liquibase with spring.liquibase.url",
                (Object)
                    new String[] {
                      "--spring.liquibase.enabled=true",
                      "--spring.liquibase.url=%s",
                      "--spring.liquibase.user=test",
                      "--spring.liquibase.change-log=classpath:migration/liquibase/changelog.sql"
                    }));

    @ParameterizedTest
    @FieldSource("MIGRATIONS")
    @Timeout(value = TEST_LIMIT_SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    @DisplayName("stops startup within 10 s with the driver's connection error")
    void stopsStartupInSeconds(String... migration) throws IOException {
      try (var database = new SilentDatabase()) {
        var application =
            new SpringApplicationBuilder(MigrationApplication.class).web(WebApplicationType.NONE);

        long start = System.nanoTime();
        var failure = catchThrowable(() -> application.run(runArguments(database, migration)));
        var took = since(start);
        if (log.isInfoEnabled()) {
          log.info("The migration's startup failed after {} ms", took.toMillis());
        }

        assertThat(failure).isNotNull();
        assertThat(chainOf(failure))
            .anySatisfy(cause -> assertThat(cause).isInstanceOf(SQLException.class));
        assertThat(took).isLessThan(BOUND);
      }
    }

    /** Returns the run arguments of a migration against the silent database. */
    private String[] runArguments(SilentDatabase database, String... migration) {
      return Stream.of(
              Arrays.stream(migration).map(argument -> argument.formatted(database.url())),
              Stream.of("--spring.datasource.url=" + database.url()),
              SilentDatabase.STARTUP_PROPERTIES.stream().map(property -> "--" + property))
          .flatMap(arguments -> arguments)
          .toArray(String[]::new);
    }
  }

  @Nested
  @DisplayName("on the test database")
  class Cost {

    /** How many connections each measurement opens. */
    private static final int CONNECTIONS = 30;

    /** How many threads the thread measurement starts. */
    private static final int THREADS = 1000;

    @Test
    @DisplayName("a connection opened with the limit works; the driver's thread costs are printed")
    void measuresDriverThread() throws Exception {
      try (var context =
          new SpringApplicationBuilder(
                  BoundaryApplication.class, PostgresContainerConfiguration.class)
              .web(WebApplicationType.NONE)
              .initializers(
                  app ->
                      app.getBeanFactory().registerSingleton(DataConstants.TLS_CHECK, new Object()))
              .properties(SilentDatabase.STARTUP_PROPERTIES.toArray(String[]::new))
              .run()) {
        var details = context.getBean(JdbcConnectionDetails.class);
        var withoutLimit = new long[CONNECTIONS];
        var withLimit = new long[CONNECTIONS];
        for (int i = 0; i < CONNECTIONS; i++) {
          withoutLimit[i] = connectNanos(details, "0");
          withLimit[i] = connectNanos(details, "3");
        }

        if (log.isInfoEnabled()) {
          log.info(
              "Median connection time to the test database: {} µs with loginTimeout=0, {} µs with"
                  + " loginTimeout=3; median start and join of one platform thread: {} µs",
              median(withoutLimit) / NANOS_PER_MICRO,
              median(withLimit) / NANOS_PER_MICRO,
              median(threadNanos()) / NANOS_PER_MICRO);
        }
        assertThat(withLimit).hasSize(CONNECTIONS).doesNotContain(0L);
      }
    }

    /** Opens and closes one connection with a login limit, and returns how long it took to open. */
    private long connectNanos(JdbcConnectionDetails details, String loginTimeout)
        throws SQLException {
      var properties = new Properties();
      properties.setProperty("user", details.getUsername());
      properties.setProperty("password", details.getPassword());
      properties.setProperty("loginTimeout", loginTimeout);
      long start = System.nanoTime();
      try (var connection = new Driver().connect(details.getJdbcUrl(), properties)) {
        long took = System.nanoTime() - start;
        assertThat(connection).isNotNull();
        assertThat(connection.isValid(1)).isTrue();
        return took;
      }
    }

    /** Returns how long each of many platform threads took to start, run nothing and end. */
    private long[] threadNanos() throws InterruptedException {
      var took = new long[THREADS];
      for (int i = 0; i < THREADS; i++) {
        long start = System.nanoTime();
        var thread = Thread.ofPlatform().daemon().start(() -> {});
        thread.join();
        took[i] = System.nanoTime() - start;
      }
      return took;
    }

    /** Returns the median of the measured times. */
    private double median(long... nanos) {
      var sorted = LongStream.of(nanos).sorted().toArray();
      return (double) sorted[sorted.length / 2];
    }
  }
}
