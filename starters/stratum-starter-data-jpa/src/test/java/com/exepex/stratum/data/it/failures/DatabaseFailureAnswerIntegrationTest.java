package com.exepex.stratum.data.it.failures;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.BackwardCompatibleErrorChecks;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import com.exepex.stratum.test.WebTestSupport;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(
    classes = FailuresApplication.class,
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:failures-schema.sql",
      "spring.datasource.hikari.maximum-pool-size=" + StrictModeAnswers.POOL_SIZE,
      "spring.datasource.hikari.connection-timeout=250",
      "stratum.web.error.statuses."
          + StrictModeAnswers.CONFLICT
          + "="
          + StrictModeAnswers.CONFLICT_STATUS
    })
@Import(PostgresContainerConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("On PostgreSQL, a request that fails because of the database gets its own answer")
class DatabaseFailureAnswerIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  /** Empties the customers and gives each test two accounts at version 0. */
  @BeforeEach
  void resetRows() {
    jdbcTemplate.execute("TRUNCATE customers, accounts");
    jdbcTemplate.update("INSERT INTO accounts (id, balance, version) VALUES (1, 0, 0), (2, 0, 0)");
  }

  /** Checks that a path gets Spring Boot's own 500 body, with no Stratum code, as before. */
  private void checkBootAnswer(int port, String path) throws IOException, InterruptedException {
    BackwardCompatibleErrorChecks.checkBootBodyStatus(
        port,
        path,
        HttpStatus.INTERNAL_SERVER_ERROR.value(),
        HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
    assertThat(WebTestSupport.getFromService(port, path).body()).doesNotContain("SX-WEB-101");
  }

  @Nested
  @TestPropertySource(properties = "spring.main.web-application-type=servlet")
  @DisplayName("on Spring MVC, outside backward-compatible mode")
  class ServletStrict extends StrictModeAnswers {

    @LocalServerPort private int serverPort;

    @Override
    int port() {
      return serverPort;
    }
  }

  @Nested
  @TestPropertySource(properties = "spring.main.web-application-type=reactive")
  @DisplayName("on Spring WebFlux, outside backward-compatible mode")
  class ReactiveStrict extends StrictModeAnswers {

    @LocalServerPort private int serverPort;

    @Override
    int port() {
      return serverPort;
    }
  }

  @Nested
  @TestPropertySource(
      properties = {"spring.main.web-application-type=servlet", "stratum.backward-compatible=true"})
  @DisplayName("on Spring MVC, in backward-compatible mode")
  class ServletCompatible {

    @LocalServerPort private int port;

    @Test
    @DisplayName("a duplicate key and a database timeout keep Spring Boot's 500, as before")
    void answersAsBefore() throws IOException, InterruptedException {
      checkBootAnswer(port, FailuresApplication.DUPLICATE);
      checkBootAnswer(port, FailuresApplication.DATABASE_TIMEOUT);
    }
  }

  @Nested
  @TestPropertySource(
      properties = {
        "spring.main.web-application-type=reactive",
        "stratum.backward-compatible=true"
      })
  @DisplayName("on Spring WebFlux, in backward-compatible mode")
  class ReactiveCompatible {

    @LocalServerPort private int port;

    @Test
    @DisplayName("a duplicate key and a database timeout keep Spring Boot's 500, as before")
    void answersAsBefore() throws IOException, InterruptedException {
      checkBootAnswer(port, FailuresApplication.DUPLICATE);
      checkBootAnswer(port, FailuresApplication.DATABASE_TIMEOUT);
    }
  }
}
