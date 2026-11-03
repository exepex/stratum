package com.exepex.stratum.data;

import static com.exepex.stratum.data.testsupport.DataAssertions.assertChainCarriesNo;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.INVALID_TEXT;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.PERSONAL_VALUE;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.postgresFailure;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.postgresReaders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Wrapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.mockito.invocation.InvocationOnMock;
import org.postgresql.util.PSQLException;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("JdbcBoundary wraps every JDBC object a data source hands out and cleans its errors")
class JdbcBoundaryTest {

  /** Every type the boundary generates a wrapper for: the data source and what it hands out. */
  private static final List<Class<?>> GENERATED_TYPES =
      Stream.concat(Stream.of(DataSource.class), JdbcWrapperClasses.WRAPPED_TYPES.stream())
          .toList();

  /** Names each case of a parameterized test by the method it calls. */
  private static final String METHOD_NAME = "{0}";

  /** The value passed for each parameter type that needs one. */
  private static final Map<Class<?>, Object> FIXED_ARGUMENTS =
      Map.of(
          boolean.class,
          false,
          char.class,
          'x',
          byte.class,
          (byte) 0,
          short.class,
          (short) 0,
          int.class,
          0,
          long.class,
          0L,
          float.class,
          0.0f,
          double.class,
          0.0d,
          String.class,
          "x",
          Class.class,
          Object.class);

  /** The boundary, generated once for the whole class. */
  private final JdbcBoundary boundary =
      new JdbcBoundary(new JdbcWrapperClasses(), postgresReaders());

  /** The wrapper of a data source, the parent of every other wrapper in these tests. */
  private final JdbcWrapper dataSource = (JdbcWrapper) boundary.wrap(mock(DataSource.class));

  /** Returns every method of every generated type that matches, as (name, type, method). */
  private static List<Arguments> methodsWhere(Predicate<Method> matches) {
    return GENERATED_TYPES.stream()
        .flatMap(
            type ->
                Arrays.stream(type.getMethods())
                    .filter(matches)
                    .map(
                        method ->
                            arguments(
                                type.getSimpleName()
                                    + "."
                                    + method.getName()
                                    + Arrays.toString(method.getParameterTypes()),
                                type,
                                method)))
        .toList();
  }

  /** Returns whether a method declares an {@code SQLException} or one of its subclasses. */
  private static boolean throwsSqlException(Method method) {
    return Arrays.stream(method.getExceptionTypes()).anyMatch(SQLException.class::isAssignableFrom);
  }

  /** Returns whether a parameter type is one the boundary unwraps before the driver gets it. */
  private static boolean isUnwrapped(Class<?> parameterType) {
    return parameterType == Object.class
        || JdbcWrapperClasses.WRAPPED_TYPES.contains(parameterType);
  }

  /** Returns the wrapper of a mock of a type, as the boundary hands it out. */
  private Object wrapperOf(Class<?> type, Object delegate) {
    return type == DataSource.class
        ? boundary.wrap((DataSource) delegate)
        : boundary.wrapResult(delegate, type, dataSource);
  }

  /** Returns arguments a method accepts: zero, empty or {@code null}, by parameter type. */
  private Object[] argumentsFor(Method method) {
    return Arrays.stream(method.getParameterTypes()).map(this::argumentOf).toArray();
  }

  /** Returns a value of a parameter type: an empty array, a fixed value or {@code null}. */
  private @Nullable Object argumentOf(Class<?> type) {
    return type.isArray()
        ? java.lang.reflect.Array.newInstance(type.getComponentType(), 0)
        : FIXED_ARGUMENTS.get(type);
  }

  /** Answers every call on a mock with PostgreSQL's error, which names the call and the value. */
  private Object failWithPersonalValue(InvocationOnMock call) throws SQLException {
    throw postgresFailure(INVALID_TEXT, call.getMethod().getName());
  }

  /** Calls a method on a wrapper and returns what it threw, or {@code null}. */
  private Throwable thrownBy(Object wrapper, Method method, Object... arguments)
      throws IllegalAccessException {
    try {
      method.invoke(wrapper, arguments);
      return null;
    } catch (InvocationTargetException failure) {
      return failure.getTargetException();
    }
  }

  @Nested
  @DisplayName("errors")
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class Errors {

    /** Every method that can throw an SQLException, default methods and unwrap included. */
    private static final List<Arguments> THROWING_METHODS =
        methodsWhere(JdbcBoundaryTest::throwsSqlException);

    @ParameterizedTest(name = METHOD_NAME)
    @FieldSource("THROWING_METHODS")
    @DisplayName("every throwing method throws the clean copy of the driver's error")
    void cleansEveryThrowingMethod(String name, Class<?> type, Method method)
        throws IllegalAccessException {
      var delegate = mock(type, JdbcBoundaryTest.this::failWithPersonalValue);

      var thrown = thrownBy(wrapperOf(type, delegate), method, argumentsFor(method));

      assertThat(thrown).isInstanceOf(SQLException.class).isNotInstanceOf(PSQLException.class);
      assertChainCarriesNo(thrown, PERSONAL_VALUE);
    }

    @Test
    @DisplayName("an error that is no SQLException passes unchanged")
    void passesOtherFailures() throws SQLException {
      var failure = new IllegalStateException("closed");
      try (var connection = mock(Connection.class);
          var wrapper = (Connection) wrapperOf(Connection.class, connection)) {
        when(connection.createStatement()).thenThrow(failure);

        assertThatThrownBy(wrapper::createStatement).isSameAs(failure);
      }
    }
  }

  @Nested
  @DisplayName("results")
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class Results {

    /** Every method that returns a wrapped JDBC type. */
    private static final List<Arguments> JDBC_RESULTS =
        methodsWhere(method -> JdbcWrapperClasses.WRAPPED_TYPES.contains(method.getReturnType()));

    /** Every method that returns {@code Object}, such as {@code getObject}. */
    private static final List<Arguments> OBJECT_RESULTS =
        methodsWhere(
            method ->
                method.getReturnType() == Object.class
                    && method.getDeclaringClass() != Wrapper.class);

    /** Every method that returns warnings. */
    private static final List<Arguments> WARNING_RESULTS =
        methodsWhere(method -> method.getReturnType() == SQLWarning.class);

    @ParameterizedTest(name = METHOD_NAME)
    @FieldSource("JDBC_RESULTS")
    @DisplayName("every JDBC object a method returns is wrapped")
    void wrapsEveryJdbcResult(String name, Class<?> type, Method method)
        throws ReflectiveOperationException {
      var returned = mock(method.getReturnType());
      var delegate = mock(type, invocation -> returned);

      var result = method.invoke(wrapperOf(type, delegate), argumentsFor(method));

      assertThat(result).isInstanceOf(JdbcWrapper.class).isInstanceOf(method.getReturnType());
      assertThat(((JdbcWrapper) result).delegate).isSameAs(returned);
    }

    @ParameterizedTest(name = METHOD_NAME)
    @FieldSource("OBJECT_RESULTS")
    @DisplayName("a JDBC object returned as Object, such as a Blob from getObject, is wrapped")
    void wrapsJdbcObjectReturnedAsObject(String name, Class<?> type, Method method)
        throws ReflectiveOperationException {
      var blob = mock(Blob.class);
      var delegate = mock(type, invocation -> blob);

      var result = method.invoke(wrapperOf(type, delegate), argumentsFor(method));

      assertThat(result).isInstanceOf(JdbcWrapper.class).isInstanceOf(Blob.class);
    }

    @ParameterizedTest(name = METHOD_NAME)
    @FieldSource("WARNING_RESULTS")
    @DisplayName("every warning a method returns is cleaned, and no warning stays null")
    void cleansEveryWarning(String name, Class<?> type, Method method)
        throws ReflectiveOperationException {
      var warning = new SQLWarning("NOTICE: " + PERSONAL_VALUE, "00000");
      var wrapper = wrapperOf(type, mock(type, invocation -> warning));

      var result = (SQLWarning) method.invoke(wrapper, argumentsFor(method));

      assertChainCarriesNo(result, PERSONAL_VALUE);
      assertThat(method.invoke(wrapperOf(type, mock(type)), argumentsFor(method))).isNull();
    }

    @Test
    @DisplayName("a value that is no JDBC object, or null, comes back as it is")
    void keepsPlainValues() throws SQLException {
      try (var resultSet = mock(ResultSet.class);
          var wrapper = (ResultSet) wrapperOf(ResultSet.class, resultSet)) {
        when(resultSet.getObject(1)).thenReturn(PERSONAL_VALUE);

        assertThat(wrapper.getObject(1)).isEqualTo(PERSONAL_VALUE);
        assertThat(wrapper.getObject(2)).isNull();
      }
    }

    @Test
    @DisplayName("a statement's connection is the wrapper that created the statement")
    void returnsParentForBackReference() throws SQLException {
      try (var connection = mock(Connection.class);
          var statement = mock(Statement.class);
          var wrapper = (Connection) wrapperOf(Connection.class, connection)) {
        when(connection.createStatement()).thenReturn(statement);
        when(statement.getConnection()).thenReturn(connection);

        try (var wrappedStatement = wrapper.createStatement()) {
          assertThat(wrappedStatement.getConnection()).isSameAs(wrapper);
        }
      }
    }

    @Test
    @DisplayName(
        "an object that is already a wrapper, from a data source bean inside, is not wrapped again")
    void keepsInnerWrapper() throws SQLException {
      var inner =
          (DataSource) wrapperOf(DataSource.class, dataSourceGiving(mock(Connection.class)));
      try (var innerConnection = inner.getConnection();
          var outerConnection =
              ((DataSource) wrapperOf(DataSource.class, dataSourceGiving(innerConnection)))
                  .getConnection()) {
        assertThat(outerConnection).isSameAs(innerConnection);
      }
    }

    /** Returns a data source whose every connection is the given one. */
    private DataSource dataSourceGiving(Connection connection) throws SQLException {
      var source = mock(DataSource.class);
      when(source.getConnection()).thenReturn(connection);
      return source;
    }
  }

  @Nested
  @DisplayName("arguments and unwrapping")
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class Unwrapping {

    /** Every method with a parameter the boundary unwraps: a JDBC object or an Object. */
    private static final List<Arguments> UNWRAPPING_METHODS =
        methodsWhere(
            method ->
                method.getDeclaringClass() != Wrapper.class
                    && Arrays.stream(method.getParameterTypes())
                        .anyMatch(JdbcBoundaryTest::isUnwrapped));

    @ParameterizedTest(name = METHOD_NAME)
    @FieldSource("UNWRAPPING_METHODS")
    @DisplayName("every JDBC object passed back to the driver is the driver's own")
    void unwrapsEveryJdbcArgument(String name, Class<?> type, Method method)
        throws ReflectiveOperationException {
      var parameterTypes = method.getParameterTypes();
      var driverObjects = Arrays.stream(parameterTypes).map(this::driverObjectFor).toArray();
      var arguments =
          IntStream.range(0, parameterTypes.length)
              .mapToObj(index -> argumentFor(parameterTypes[index], driverObjects[index]))
              .toArray();
      var delegate = mock(type);

      method.invoke(wrapperOf(type, delegate), arguments);

      var received = mockingDetails(delegate).getInvocations().iterator().next().getArguments();
      IntStream.range(0, parameterTypes.length)
          .filter(index -> driverObjects[index] != null)
          .forEach(
              index ->
                  assertThat(received[index])
                      .as(name + " argument " + index)
                      .isSameAs(driverObjects[index]));
    }

    /** Returns the driver's object for an unwrapped parameter: a Blob for an Object one. */
    private @Nullable Object driverObjectFor(Class<?> parameterType) {
      return isUnwrapped(parameterType) ? mock(jdbcTypeOf(parameterType)) : null;
    }

    /** Returns the argument: the wrapper of the driver's object, or a plain value. */
    private @Nullable Object argumentFor(Class<?> parameterType, @Nullable Object driverObject) {
      return driverObject == null
          ? argumentOf(parameterType)
          : wrapperOf(jdbcTypeOf(parameterType), driverObject);
    }

    /** Returns the JDBC type passed for a parameter type: a Blob for an Object parameter. */
    private Class<?> jdbcTypeOf(Class<?> parameterType) {
      return parameterType == Object.class ? Blob.class : parameterType;
    }

    @Test
    @DisplayName("unwrap and isWrapperFor reach the pool and the driver through the wrapper")
    void passesUnwrapThrough() throws SQLException {
      var pool = mock(DataSource.class);
      var poolClass = mock(Connection.class);
      when(pool.isWrapperFor(Connection.class)).thenReturn(true);
      when(pool.unwrap(Connection.class)).thenReturn(poolClass);
      var wrapper = (DataSource) wrapperOf(DataSource.class, pool);

      assertThat(wrapper.isWrapperFor(Connection.class)).isTrue();
      assertThat(wrapper.unwrap(Connection.class)).isSameAs(poolClass);
    }
  }
}
