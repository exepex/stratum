package com.exepex.stratum.data.it;

import static com.exepex.stratum.test.DatabaseFailureAssertions.PERSONAL_VALUE;
import static com.exepex.stratum.test.DatabaseFailureAssertions.chainOf;
import static com.exepex.stratum.test.DatabaseFailureAssertions.sqlExceptionIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.it.boundary.BoundaryApplication;
import com.exepex.stratum.data.it.boundary.BoundaryServiceTest;
import com.exepex.stratum.data.testsupport.DataAssertions;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.hibernate.exception.AuthException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@BoundaryServiceTest
@DisplayName("Through the boundary, Spring and Hibernate translate PostgreSQL errors as before")
class JdbcBoundaryTranslationIntegrationTest {

  /** A role that may read nothing, for an access denied by the database. */
  private static final String READER_ROLE = "boundary_reader";

  @Autowired private DataSource dataSource;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private TransactionTemplate transactionTemplate;

  @Autowired private EntityManager entityManager;

  @Autowired private EntityManagerFactory entityManagerFactory;

  @Autowired private JdbcConnectionDetails connectionDetails;

  @Autowired
  @Qualifier(DataConstants.JDBC_BOUNDARY)
  private BeanPostProcessor boundary;

  /** Empties the tables and adds the role that may read nothing. */
  @BeforeEach
  void emptyTablesAndAddReaderRole() {
    jdbcTemplate.execute(BoundaryApplication.EMPTY_TABLES);
    jdbcTemplate.execute(
        "DO $$ BEGIN CREATE ROLE "
            + READER_ROLE
            + " NOLOGIN;"
            + " EXCEPTION WHEN duplicate_object THEN NULL; END $$");
  }

  /** Returns what Hibernate makes of a database error. */
  private Throwable hibernateTranslationOf(SQLException failure) {
    return DataAssertions.hibernateTranslationOf(entityManagerFactory, failure);
  }

  /** Returns a data source of the test database with a wrong password, outside the boundary. */
  private DataSource wrongPasswordDataSource() {
    return new SimpleDriverDataSource(
        new Driver(), connectionDetails.getJdbcUrl(), connectionDetails.getUsername(), "wrong");
  }

  /** Returns the same data source with a wrong password, wrapped as a bean would be. */
  private DataSource wrappedWrongPasswordDataSource() {
    return (DataSource)
        boundary.postProcessAfterInitialization(wrongPasswordDataSource(), "rejected");
  }

  /** Returns the error of a connection attempt. */
  private SQLException connectionFailureOf(DataSource rejecting) {
    return catchThrowableOfType(SQLException.class, rejecting::getConnection);
  }

  /** Returns the class of a translation, or {@code null} when the translator found none. */
  private @Nullable Class<?> classOf(@Nullable Object translation) {
    return translation == null ? null : translation.getClass();
  }

  /**
   * Returns what Hibernate throws for one insert of the personal value. A single statement, not
   * Hibernate's batch of entities, whose BatchUpdateException no mapping of plain SQLExceptions
   * would change.
   */
  private Throwable failureOfInserting(String insert) {
    return catchThrowable(
        () ->
            transactionTemplate.execute(
                status ->
                    entityManager
                        .createNativeQuery(insert)
                        .setParameter(1, PERSONAL_VALUE)
                        .executeUpdate()));
  }

  /** Returns Hibernate's constraint violation in a failure's chain. */
  private ConstraintViolationException violationIn(Throwable failure) {
    return chainOf(failure)
        .filter(ConstraintViolationException.class::isInstance)
        .map(ConstraintViolationException.class::cast)
        .findFirst()
        .orElseThrow();
  }

  @Nested
  @DisplayName("results that a SQLState-to-class mapping would change")
  class ByState {

    @Test
    @DisplayName("a duplicate email gives Hibernate's UNIQUE violation, with the constraint name")
    void keepsUniqueKind() {
      jdbcTemplate.update("INSERT INTO customers VALUES (1, ?)", PERSONAL_VALUE);

      var violation = violationIn(failureOfInserting("INSERT INTO customers VALUES (2, ?1)"));

      assertThat(violation.getKind()).isEqualTo(ConstraintKind.UNIQUE);
      assertThat(violation.getConstraintName()).isEqualTo("customers_email_key");
    }

    @Test
    @DisplayName("a payment of an unknown customer gives Hibernate's FOREIGN_KEY violation")
    void keepsForeignKeyKind() {
      var violation = violationIn(failureOfInserting("INSERT INTO payments VALUES (1, ?1)"));

      // SQLStateConversionDelegate.java:97 maps 23503 to FOREIGN_KEY.
      assertThat(violation.getKind()).isEqualTo(ConstraintKind.FOREIGN_KEY);
    }

    @Test
    @DisplayName("a read the database denies (42501) gives Hibernate's AuthException")
    void keepsAuthExceptionForDeniedRead() {
      var failure =
          catchThrowable(
              () -> transactionTemplate.executeWithoutResult(status -> readAs(READER_ROLE)));

      assertThat(hibernateTranslationOf(sqlExceptionIn(failure))).isInstanceOf(AuthException.class);
    }

    @Test
    @DisplayName("a wrong password (28P01) gives Hibernate's AuthException")
    void keepsAuthExceptionForWrongPassword() {
      assertThat(hibernateTranslationOf(connectionFailureOf(wrappedWrongPasswordDataSource())))
          .isInstanceOf(AuthException.class);
    }

    @Test
    @DisplayName("an unsupported feature (0A000) and a wrong password give Spring the same types")
    void keepsSpringTypes() throws SQLException {
      var raw = new JdbcTemplate(dataSource.unwrap(HikariDataSource.class));
      var unsupported = "SELECT COUNT(*) FROM customers FOR UPDATE";
      var translator = jdbcTemplate.getExceptionTranslator();

      assertThat(catchThrowable(() -> jdbcTemplate.queryForList(unsupported)))
          .isExactlyInstanceOf(catchThrowable(() -> raw.queryForList(unsupported)).getClass());
      var cleanFailure = connectionFailureOf(wrappedWrongPasswordDataSource());
      var rawFailure = connectionFailureOf(wrongPasswordDataSource());
      assertThat(classOf(translator.translate("probe", null, cleanFailure)))
          .isEqualTo(classOf(translator.translate("probe", null, rawFailure)));
    }

    /** Reads the customers as a role that may not, within the current transaction. */
    private void readAs(String role) {
      jdbcTemplate.execute("SET LOCAL ROLE " + role);
      jdbcTemplate.queryForList("SELECT * FROM customers");
    }
  }

  @Nested
  @DisplayName("results that no mapping changes")
  class Unchanged {

    @Test
    @DisplayName("a payment of an unknown customer gives Spring's DataIntegrityViolationException")
    void keepsDataIntegrityViolation() {
      assertThat(
              catchThrowable(
                  () -> jdbcTemplate.update("INSERT INTO payments VALUES (1, ?)", PERSONAL_VALUE)))
          .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName(
        "a row another transaction has locked (55P03) gives Spring the same type as before")
    void keepsLockNotAvailableType() throws SQLException {
      jdbcTemplate.update("INSERT INTO customers VALUES (1, ?)", PERSONAL_VALUE);
      var raw = new JdbcTemplate(dataSource.unwrap(HikariDataSource.class));
      var lockNow = "SELECT * FROM customers WHERE id = 1 FOR UPDATE NOWAIT";
      try (var holder = dataSource.getConnection();
          var lock = holder.createStatement()) {
        holder.setAutoCommit(false);
        lock.execute("SELECT * FROM customers WHERE id = 1 FOR UPDATE");

        assertThat(catchThrowable(() -> jdbcTemplate.queryForList(lockNow)))
            .isExactlyInstanceOf(catchThrowable(() -> raw.queryForList(lockNow)).getClass());
        holder.rollback();
      }
    }
  }
}
