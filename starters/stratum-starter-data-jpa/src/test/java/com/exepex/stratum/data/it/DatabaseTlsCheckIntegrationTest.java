package com.exepex.stratum.data.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.data.it.boundary.BoundaryApplication;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Starts the service and reads its console, since Spring Boot sets the logging up again at startup
 * and drops any appender a test adds before.
 */
@Slf4j
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("On PostgreSQL, the TLS check warns after startup about an unencrypted data source")
class DatabaseTlsCheckIntegrationTest {

  /** How long a test waits for the check's line. */
  private static final Duration LINE_WAIT = Duration.ofSeconds(60);

  /**
   * The driver's own login limit, in seconds. Without it a database that never answers holds the
   * check for as long as the connection stays open: the driver's {@code loginTimeout} defaults to
   * 0, which it reads before HikariCP's login timeout.
   */
  private static final int LOGIN_TIMEOUT_SECONDS = 2;

  /** How many connections the hung database's system accepts without anyone reading them. */
  private static final int BACKLOG = 50;

  /** The host of the database that never answers: this machine's own loopback address. */
  private static final InetAddress HUNG_HOST = InetAddress.getLoopbackAddress();

  /** The thread the check runs on, as Logback's console line shortens it. */
  private static final String THREAD = "abase-tls-check]";

  /** The WARN for an unencrypted data source named dataSource. */
  private static final String UNENCRYPTED =
      "The data source 'dataSource' connects to the database without TLS.";

  /** The WARN for a data source named dataSource that could not be checked. */
  private static final String NOT_CHECKED =
      "Could not check whether the data source 'dataSource' uses TLS: ";

  /** Returns the console lines that hold a text. */
  private long linesWith(CapturedOutput output, String text) {
    return output.getOut().lines().filter(line -> line.contains(text)).count();
  }

  @Test
  @DisplayName("the test database, which has no TLS, gets one WARN from the check's own thread")
  void warnsAboutTestDatabase(CapturedOutput output) {
    var application =
        new SpringApplicationBuilder(
                BoundaryApplication.class, PostgresContainerConfiguration.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:boundary-schema.sql");

    try (var context = application.run()) {
      assertThat(context.isActive()).isTrue();
      await().atMost(LINE_WAIT).until(() -> linesWith(output, UNENCRYPTED) > 0);

      assertThat(linesWith(output, UNENCRYPTED)).isOne();
      assertThat(output.getOut().lines().filter(line -> line.contains(UNENCRYPTED)))
          .singleElement()
          .asString()
          .contains(" WARN ", THREAD)
          .doesNotContain("jdbc:");
    }
  }

  @Test
  @DisplayName("a database that never answers, with the driver's login limit, gives one WARN")
  void warnsAboutHungDatabase(CapturedOutput output) throws IOException {
    // The system accepts the connections into the backlog; nothing ever reads or answers them.
    try (var hungDatabase = new ServerSocket(0, BACKLOG, HUNG_HOST)) {
      var took = runAgainstHungDatabase(output, hungDatabase.getLocalPort());

      assertThat(took).isLessThan(LINE_WAIT);
    }
  }

  /** Starts the service on the hung database and waits for the check's WARN. */
  private Duration runAgainstHungDatabase(CapturedOutput output, int port) {
    var application =
        new SpringApplicationBuilder(BoundaryApplication.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.datasource.url=jdbc:postgresql://"
                    + HUNG_HOST.getHostAddress()
                    + ":"
                    + port
                    + "/orders?loginTimeout="
                    + LOGIN_TIMEOUT_SECONDS,
                "spring.sql.init.mode=never",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
                "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect");

    try (var context = application.run()) {
      long ready = System.nanoTime();
      assertThat(context.isActive()).isTrue();
      await().atMost(LINE_WAIT).until(() -> linesWith(output, NOT_CHECKED) > 0);
      var took = Duration.ofNanos(System.nanoTime() - ready);
      if (log.isInfoEnabled()) {
        log.info(
            "The check of a database that does not answer took {} ms after the service was up",
            took.toMillis());
      }

      assertThat(linesWith(output, NOT_CHECKED)).isOne();
      assertThat(output.getOut().lines().filter(line -> line.contains(NOT_CHECKED)))
          .singleElement()
          .asString()
          .doesNotContain(HUNG_HOST.getHostAddress(), String.valueOf(port));
      return took;
    }
  }
}
