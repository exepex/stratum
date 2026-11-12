package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.mockito.invocation.InvocationOnMock;

@DisplayName("PostgresEncryptionReader asks pg_stat_ssl whether the session uses TLS")
class PostgresEncryptionReaderTest {

  /** The owner's fixed limit on the query, in seconds, which has no key. */
  private static final int OWNER_LIMIT_SECONDS = 5;

  private final PostgresEncryptionReader reader = new PostgresEncryptionReader();

  private final Connection connection = mock(Connection.class);

  private final PreparedStatement statement = mock(PreparedStatement.class);

  /** Whether the session's query finds its row. */
  private boolean hasRow;

  /** The session's ssl column. */
  private boolean ssl;

  /** The session's row, which answers from the two fields above. */
  private final ResultSet row = mock(ResultSet.class, this::answerRow);

  /** Answers a call on the row: whether there is one, and its ssl column. */
  private @Nullable Object answerRow(InvocationOnMock call) {
    return switch (call.getMethod().getName()) {
      case "next" -> hasRow;
      case "getBoolean" -> ssl;
      default -> null;
    };
  }

  /** Makes the session's query answer with the given row. */
  private void answer(boolean rowFound, boolean sslColumn) throws SQLException {
    hasRow = rowFound;
    ssl = sslColumn;
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(row);
  }

  @Nested
  @DisplayName("which databases it reads")
  class Databases {

    /**
     * Names other drivers give, and near misses of PostgreSQL's, which the reader must not read.
     */
    private static final List<String> OTHER_DATABASES = List.of("H2", "MySQL", "postgresql", "");

    @Test
    @DisplayName("PostgreSQL, by the name its driver gives")
    void readsPostgres() {
      assertThat(reader.reads("PostgreSQL")).isTrue();
    }

    @ParameterizedTest
    @FieldSource("OTHER_DATABASES")
    @DisplayName("no other database")
    void readsNoOther(String databaseProductName) {
      assertThat(reader.reads(databaseProductName)).isFalse();
    }
  }

  @Nested
  @DisplayName("what it answers")
  class Answers {

    /** Both values of the session's ssl column. */
    private static final List<Boolean> SSL_VALUES = List.of(true, false);

    @ParameterizedTest
    @FieldSource("SSL_VALUES")
    @DisplayName("the session's ssl column")
    void answersSsl(boolean sslColumn) throws SQLException {
      answer(true, sslColumn);

      assertThat(reader.encrypted(connection)).isEqualTo(sslColumn);
    }

    @Test
    @DisplayName("the query runs within the fixed 5 second limit")
    void limitsQuery() throws SQLException {
      answer(true, true);

      reader.encrypted(connection);

      verify(statement).setQueryTimeout(OWNER_LIMIT_SECONDS);
    }

    @Test
    @DisplayName("no row for the session is a failure to check, not an answer")
    void noRowFails() throws SQLException {
      answer(false, true);

      assertThatThrownBy(() -> reader.encrypted(connection)).isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("the statement and its rows are closed")
    void closesStatement() throws SQLException {
      answer(true, true);

      reader.encrypted(connection);

      verify(row).close();
      verify(statement).close();
    }
  }
}
