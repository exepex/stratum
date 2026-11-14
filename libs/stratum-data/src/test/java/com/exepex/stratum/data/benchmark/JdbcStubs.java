package com.exepex.stratum.data.benchmark;

import com.exepex.stratum.data.JdbcBoundary;
import com.exepex.stratum.data.JdbcWrapperClasses;
import com.exepex.stratum.data.StatementSpans;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.function.BiFunction;
import javax.sql.DataSource;
import lombok.experimental.UtilityClass;

/**
 * JDBC stubs for benchmarks with no database: JDK proxies whose methods answer by name at once, and
 * a statement through the boundary over them.
 */
@UtilityClass
class JdbcStubs {

  /**
   * Returns a connection stub with a database name and product, whose prepared statements return an
   * empty result. JDK proxies answer at once; Mockito's stubs cost microseconds per call, more than
   * the span itself.
   *
   * @return the connection stub
   */
  Connection stubConnection() {
    Object rows = stub(ResultSet.class, (method, args) -> null);
    Object statement =
        stub(
            PreparedStatement.class, (method, args) -> "executeQuery".equals(method) ? rows : null);
    var metadata =
        stub(
            DatabaseMetaData.class,
            (method, args) -> "getDatabaseProductName".equals(method) ? "PostgreSQL" : null);
    return stub(Connection.class, (method, args) -> connectionAnswer(method, metadata, statement));
  }

  /** Returns what the connection stub answers to a method. */
  private Object connectionAnswer(String method, DatabaseMetaData metadata, Object statement) {
    return switch (method) {
      case "getCatalog" -> "orders";
      case "getMetaData" -> metadata;
      case "prepareStatement" -> statement;
      default -> null;
    };
  }

  /** Returns a JDK proxy of a JDBC type whose methods answer by name; void methods do nothing. */
  private <T> T stub(Class<T> type, BiFunction<String, Object[], Object> answer) {
    return type.cast(
        Proxy.newProxyInstance(
            Thread.currentThread().getContextClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> answerOf(method, answer.apply(method.getName(), args))));
  }

  /**
   * Returns the answer, or a primitive's zero value when the method returns one and the answer is
   * null.
   */
  private Object answerOf(Method method, Object answer) {
    if (answer != null || !method.getReturnType().isPrimitive()) {
      return answer;
    }
    return method.getReturnType() == boolean.class
        ? Boolean.FALSE
        : defaultNumber(method.getReturnType());
  }

  /** Returns the zero of a primitive number type. */
  private Object defaultNumber(Class<?> type) {
    if (type == int.class) {
      return 0;
    }
    return type == long.class ? 0L : null;
  }

  /**
   * Returns the prepared statement through a boundary with the spans, over the connection.
   *
   * @param spans the statements' spans
   * @param connection the connection stub
   * @param sql the statement's SQL
   * @return the statement wrapper
   * @throws SQLException never, from the stubs
   */
  PreparedStatement boundaryStatement(StatementSpans spans, Connection connection, String sql)
      throws SQLException {
    var pool =
        stub(
            DataSource.class, (method, args) -> "getConnection".equals(method) ? connection : null);
    var dataSource =
        new JdbcBoundary(new JdbcWrapperClasses(), List.of()).withSpans(spans).wrap(pool);
    return dataSource.getConnection().prepareStatement(sql);
  }
}
