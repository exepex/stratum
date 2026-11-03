package com.exepex.stratum.data.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.it.boundary.BoundaryApplication;
import com.exepex.stratum.data.it.boundary.BoundaryServiceTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@BoundaryServiceTest
@DisplayName("PostgreSQL gets back its own savepoints and arrays through the boundary")
class JdbcBoundaryDriverObjectsIntegrationTest {

  /** The labels written as an array and read back as text. */
  private static final String[] LABELS = {"red", "blue"};

  /** Writes tag row 1 with its labels. */
  private static final String INSERT_TAGS = "INSERT INTO tags VALUES (1, ?)";

  @Autowired private DataSource dataSource;

  @Autowired private JdbcTemplate jdbcTemplate;

  /** Empties the tables, so that each test starts from no rows. */
  @BeforeEach
  void emptyTables() {
    jdbcTemplate.execute(BoundaryApplication.EMPTY_TABLES);
  }

  @Test
  @DisplayName("a nested transaction rolls back to its savepoint and the outer one commits")
  void rollsBackNestedTransaction() {
    var transactionManager = new DataSourceTransactionManager(dataSource);
    var nested = new TransactionTemplate(transactionManager);
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(outer -> writeTwoCustomersRollingBackTheSecond(nested));

    assertThat(jdbcTemplate.queryForList("SELECT id FROM customers", Long.class))
        .containsExactly(1L);
  }

  @Test
  @DisplayName("an array bound with setArray is stored as an array")
  void bindsArrayWithSetArray() {
    jdbcTemplate.update(this::insertWithSetArray);

    assertThat(storedLabels()).isEqualTo("red,blue");
  }

  @Test
  @DisplayName("an array bound with setObject is stored as an array")
  void bindsArrayWithSetObject() {
    jdbcTemplate.update(this::insertWithSetObject);

    assertThat(storedLabels()).isEqualTo("red,blue");
  }

  /** Writes customer 1, then customer 2 in a nested transaction that rolls back. */
  private void writeTwoCustomersRollingBackTheSecond(TransactionTemplate nested) {
    jdbcTemplate.update("INSERT INTO customers VALUES (1, 'first@example.com')");
    nested.executeWithoutResult(this::writeCustomerAndRollBack);
  }

  /** Writes customer 2 and marks its nested transaction for rollback. */
  private void writeCustomerAndRollBack(TransactionStatus nestedTransaction) {
    jdbcTemplate.update("INSERT INTO customers VALUES (2, 'second@example.com')");
    nestedTransaction.setRollbackOnly();
  }

  /** Returns the insert of tag row 1, its labels bound with setArray. */
  private PreparedStatement insertWithSetArray(Connection connection) throws SQLException {
    var insert = connection.prepareStatement(INSERT_TAGS);
    insert.setArray(1, connection.createArrayOf("text", LABELS));
    return insert;
  }

  /** Returns the insert of tag row 1, its labels bound with setObject. */
  private PreparedStatement insertWithSetObject(Connection connection) throws SQLException {
    var insert = connection.prepareStatement(INSERT_TAGS);
    insert.setObject(1, connection.createArrayOf("text", LABELS));
    return insert;
  }

  /** Returns the stored labels of tag row 1, joined by commas. */
  private String storedLabels() {
    return jdbcTemplate.queryForObject(
        "SELECT array_to_string(labels, ',') FROM tags WHERE id = 1", String.class);
  }
}
