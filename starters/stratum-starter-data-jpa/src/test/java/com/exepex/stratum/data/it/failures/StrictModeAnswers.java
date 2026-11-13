package com.exepex.stratum.data.it.failures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.test.WebTestSupport;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The answers to each database failure outside backward-compatible mode, which each web stack runs
 * against PostgreSQL: the status, the code, a body with nothing from the database, and one log line
 * with the exception.
 */
abstract class StrictModeAnswers {

  /** The level a data conflict is logged at. */
  private static final String WARN = "WARN";

  /** The level a busy database is logged at. */
  private static final String ERROR = "ERROR";

  /** The code of a busy database. */
  private static final String BUSY = "SX-WEB-1010";

  /** The status of a busy database, by default. */
  private static final int BUSY_STATUS = HttpStatus.SERVICE_UNAVAILABLE.value();

  /** The code of a data conflict. */
  static final String CONFLICT = "SX-WEB-1011";

  /** The status the service maps to a data conflict instead of its default 409. */
  static final int CONFLICT_STATUS = 422;

  /** What the database knows and a body must never show: the value, the constraint, the SQL. */
  private static final List<String> PRIVATE_PARTS =
      List.of(FailingAccounts.EMAIL, "customers_email_key", "accounts", "pg_sleep", "SELECT");

  /** The first line of a logged exception: a class name, not a {@code Caused by} line. */
  private static final Pattern EXCEPTION_HEADING =
      Pattern.compile("^[a-z]\\w*(\\.\\w+)+(Exception|Error)\\b.*");

  /** How long a test waits for the request it sent in the background. */
  private static final Duration REQUEST_WAIT = Duration.ofSeconds(15);

  /** The pool's size, which the pool wait holds whole. */
  static final int POOL_SIZE = 3;

  /** Sends the requests that run in the background. */
  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Autowired private DataSource dataSource;

  @Autowired private JdbcTemplate jdbcTemplate;

  /**
   * Returns the port the service listens on.
   *
   * @return the port
   */
  abstract int port();

  /** Checks the answer to a path, and that the failure was logged once at the level, whole. */
  private void checkAnswer(
      HttpResponse<String> response, CapturedOutput output, int status, String code, String level) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValue(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo(code);
    assertThat(response.body()).doesNotContain(PRIVATE_PARTS);
    var codeLines = output.getOut().lines().filter(line -> line.contains(code)).toList();
    assertThat(codeLines).singleElement().asString().contains(level);
    assertThat(output.getOut().lines().filter(EXCEPTION_HEADING.asMatchPredicate())).hasSize(1);
  }

  /** Sends a request and returns its answer. */
  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    return WebTestSupport.getFromService(port(), path);
  }

  /** Opens a connection whose transaction the test runs by hand. */
  private Connection openTransaction() throws SQLException {
    var connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    return connection;
  }

  /** Returns how many sessions wait for a lock. */
  private int sessionsWaitingForLock() {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Integer.class);
  }

  @Test
  @DisplayName(
      "a duplicate key through JPA gets SX-WEB-1011 with the mapped status, logged once at WARN")
  void duplicateKey(CapturedOutput output) throws IOException, InterruptedException {
    checkAnswer(get(FailuresApplication.DUPLICATE), output, CONFLICT_STATUS, CONFLICT, WARN);
  }

  @Test
  @DisplayName("a duplicate key in a JDBC batch gets SX-WEB-1011")
  void duplicateKeyInBatch(CapturedOutput output) throws IOException, InterruptedException {
    checkAnswer(
        get(FailuresApplication.DUPLICATE_IN_BATCH), output, CONFLICT_STATUS, CONFLICT, WARN);
  }

  @Test
  @DisplayName("an @Version row changed meanwhile gets SX-WEB-1011 when the commit notices it")
  void staleAtCommit(CapturedOutput output) throws IOException, InterruptedException {
    checkAnswer(get(FailuresApplication.STALE_AT_COMMIT), output, CONFLICT_STATUS, CONFLICT, WARN);
  }

  @Test
  @DisplayName(
      "an @Version row changed meanwhile gets SX-WEB-1011 when an explicit flush notices it")
  void staleAtFlush(CapturedOutput output) throws IOException, InterruptedException {
    checkAnswer(get(FailuresApplication.STALE_AT_FLUSH), output, CONFLICT_STATUS, CONFLICT, WARN);
  }

  @Test
  @DisplayName("a wait for a pooled connection gets 503 and SX-WEB-1010, logged once at ERROR")
  void poolWait(CapturedOutput output) throws IOException, InterruptedException, SQLException {
    try (var first = dataSource.getConnection();
        var second = dataSource.getConnection();
        var third = dataSource.getConnection()) {
      assertThat(List.of(first, second, third)).hasSize(POOL_SIZE);
      checkAnswer(get(FailuresApplication.POOLED), output, BUSY_STATUS, BUSY, ERROR);
    }
  }

  @Test
  @DisplayName("a statement the database's statement_timeout cancelled gets 503")
  void databaseTimeout(CapturedOutput output) throws IOException, InterruptedException {
    checkAnswer(get(FailuresApplication.DATABASE_TIMEOUT), output, BUSY_STATUS, BUSY, ERROR);
  }

  @Test
  @DisplayName("a lock not taken within lock_timeout gets 503")
  void lockTimeout(CapturedOutput output) throws IOException, InterruptedException, SQLException {
    try (var holder = openTransaction();
        var lock = holder.prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE")) {
      lock.setLong(1, FailingAccounts.ACCOUNT_ID);
      lock.executeQuery().close();
      try {
        checkAnswer(get(FailuresApplication.LOCK_TIMEOUT), output, BUSY_STATUS, BUSY, ERROR);
      } finally {
        holder.rollback();
      }
    }
  }

  @Test
  @DisplayName("a deadlock that PostgreSQL broke by ending the request's transaction gets 503")
  void deadlock(CapturedOutput output)
      throws IOException, InterruptedException, SQLException, ExecutionException, TimeoutException {
    try (var holder = openTransaction();
        var statement = holder.createStatement()) {
      statement.execute("SET LOCAL deadlock_timeout = '10s'");
      statement.executeUpdate("UPDATE accounts SET balance = balance WHERE id = 1");
      var answer =
          httpClient.sendAsync(
              HttpRequest.newBuilder(
                      URI.create("http://localhost:" + port() + FailuresApplication.DEADLOCK))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      await().atMost(REQUEST_WAIT).until(() -> sessionsWaitingForLock() > 0);
      try {
        statement.executeUpdate("UPDATE accounts SET balance = balance WHERE id = 2");
      } finally {
        holder.rollback();
      }
      checkAnswer(
          answer.get(REQUEST_WAIT.toSeconds(), TimeUnit.SECONDS), output, BUSY_STATUS, BUSY, ERROR);
    }
  }

  @Test
  @DisplayName("a transaction past its time limit gets 503")
  void transactionTimeout(CapturedOutput output) throws IOException, InterruptedException {
    checkAnswer(get(FailuresApplication.TRANSACTION_TIMEOUT), output, BUSY_STATUS, BUSY, ERROR);
  }
}
