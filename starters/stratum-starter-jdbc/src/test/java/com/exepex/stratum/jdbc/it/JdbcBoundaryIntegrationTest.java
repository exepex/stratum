package com.exepex.stratum.jdbc.it;

import static com.exepex.stratum.test.DatabaseFailureAssertions.PERSONAL_VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.exepex.stratum.test.DatabaseFailureAssertions;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(
    classes = JdbcApplication.class,
    properties = {
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:jdbc-schema.sql",
      "stratum.data.query-timeout=500ms"
    })
@Import(PostgresContainerConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("On PostgreSQL, JdbcClient's statements go through the JDBC boundary")
class JdbcBoundaryIntegrationTest {

  /** A statement that runs for longer than the test's 500 ms limit. */
  private static final String SLOW = "SELECT pg_sleep(5)";

  /** The insert of one customer. */
  private static final String INSERT = "INSERT INTO customers VALUES (?, ?)";

  /** Well under the 5 seconds the slow statement would run without a limit. */
  private static final Duration CANCELLED_WITHIN = Duration.ofSeconds(3);

  @Autowired private JdbcClient jdbcClient;

  /** Empties the table, so that each test starts from no rows. */
  @BeforeEach
  void emptyTable() {
    jdbcClient.sql("TRUNCATE customers").update();
  }

  @Test
  @DisplayName(
      "a duplicate email fails as Spring's DuplicateKeyException, whose log and chain hold no"
          + " email")
  void hidesDuplicateEmail(CapturedOutput output) {
    jdbcClient.sql(INSERT).params(1, PERSONAL_VALUE).update();

    var failure = catchThrowable(() -> jdbcClient.sql(INSERT).params(2, PERSONAL_VALUE).update());

    assertThat(failure).isInstanceOf(DuplicateKeyException.class);
    DatabaseFailureAssertions.assertServiceSeesNo(failure, output, PERSONAL_VALUE);
  }

  @Test
  @DisplayName("a statement past stratum.data.query-timeout is cancelled as a query timeout")
  void cancelsSlowStatement() {
    long start = System.nanoTime();

    var failure = catchThrowable(() -> jdbcClient.sql(SLOW).query().singleValue());

    assertThat(failure).isInstanceOf(QueryTimeoutException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(CANCELLED_WITHIN);
  }
}
