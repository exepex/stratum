package com.exepex.stratum.data.it;

import static com.exepex.stratum.test.DatabaseFailureAssertions.PERSONAL_VALUE;
import static com.exepex.stratum.test.DatabaseFailureAssertions.sqlExceptionIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.exepex.stratum.data.it.boundary.BoundaryApplication;
import com.exepex.stratum.data.it.routing.RoutingApplication;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import java.sql.SQLException;
import java.util.Arrays;
import javax.sql.DataSource;
import org.hibernate.dialect.PostgreSQLDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = RoutingApplication.class,
    properties = {
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:boundary-schema.sql"
    })
@Import(PostgresContainerConfiguration.class)
@DisplayName("Data source beans over data source beans keep the constraint name of an error")
class JdbcBoundaryRoutingIntegrationTest {

  @Autowired private DataSource dataSource;

  @Autowired
  @Qualifier("routingDataSource")
  private DataSource routingDataSource;

  /** Empties the tables, so that each test starts from no rows. */
  @BeforeEach
  void emptyTables() {
    new JdbcTemplate(dataSource).execute(BoundaryApplication.EMPTY_TABLES);
  }

  @Test
  @DisplayName("through a routing data source over two pools, a statement passes one boundary")
  void wrapsInnerConnectionOnce() {
    var routing = new JdbcTemplate(routingDataSource);
    routing.update("INSERT INTO customers VALUES (1, ?)", PERSONAL_VALUE);

    var failure =
        sqlExceptionIn(
            catchThrowable(
                () -> routing.update("INSERT INTO customers VALUES (2, ?)", PERSONAL_VALUE)));

    assertThat(constraintNameOf(failure)).isEqualTo("customers_email_key");
    assertThat(
            Arrays.stream(failure.getStackTrace())
                .filter(frame -> frame.getClassName().contains("StratumBoundary")))
        .hasSize(1);
  }

  @Test
  @DisplayName(
      "through a lazy proxy over the routing data source, a commit error keeps the constraint name")
  void keepsConstraintNameAtCommitThroughLazyProxy() {
    var transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    var jdbcTemplate = new JdbcTemplate(dataSource);

    var failure =
        catchThrowable(
            () ->
                transactionTemplate.executeWithoutResult(
                    status ->
                        jdbcTemplate.update("INSERT INTO invoices VALUES (1, ?)", PERSONAL_VALUE)));

    // The commit fails in Spring's transaction manager, so the dialect's extractor reads the name
    // from the SQLException in the chain, as Hibernate would.
    assertThat(constraintNameOf(sqlExceptionIn(failure))).isEqualTo("invoices_customer_fkey");
  }

  /** Returns the constraint name Hibernate's PostgreSQL dialect reads from an error. */
  private String constraintNameOf(SQLException failure) {
    return new PostgreSQLDialect()
        .getViolatedConstraintNameExtractor()
        .extractConstraintName(failure);
  }
}
