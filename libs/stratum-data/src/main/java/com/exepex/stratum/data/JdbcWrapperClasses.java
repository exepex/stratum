package com.exepex.stratum.data;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.exepex.stratum.core.api.StratumInternal;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ConnectionBuilder;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.ShardingKeyBuilder;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Wrapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.utility.RandomString;

/**
 * Generates, once at startup, the wrapper class of each JDBC type the boundary wraps, and a factory
 * that creates its wrappers with a plain constructor call, so no call reflects (ST-ENG-54).
 */
@StratumInternal
public final class JdbcWrapperClasses {

  /**
   * Every JDBC type a wrapped method can return that has a method able to throw, the most specific
   * first, so that an object implementing several gets the narrowest wrapper. {@code RowId} has no
   * such method and {@code ShardingKey} none at all, so they stay unwrapped.
   */
  static final List<Class<?>> WRAPPED_TYPES =
      List.of(
          CallableStatement.class,
          PreparedStatement.class,
          Statement.class,
          Connection.class,
          ConnectionBuilder.class,
          ShardingKeyBuilder.class,
          ResultSet.class,
          DatabaseMetaData.class,
          ResultSetMetaData.class,
          ParameterMetaData.class,
          NClob.class,
          Clob.class,
          Blob.class,
          SQLXML.class,
          Array.class,
          Savepoint.class,
          Ref.class,
          Struct.class);

  /** The start of the name of every statement method that runs SQL. */
  private static final String EXECUTE = "execute";

  /** The name of the data source methods that borrow a connection. */
  private static final String GET_CONNECTION = "getConnection";

  /** The start of the name of the connection methods that prepare a statement from its SQL. */
  private static final String PREPARE = "prepare";

  /** The name of the statement method that adds SQL to a batch. */
  private static final String ADD_BATCH = "addBatch";

  /** The name ending of every generated class, so that a stack trace shows where it came from. */
  private static final String SUFFIX = "$StratumBoundary$";

  /** The lookup that defines the generated classes next to {@link JdbcWrapper}. */
  private final MethodHandles.Lookup lookup = MethodHandles.lookup();

  /** Generates the classes. */
  private final ByteBuddy byteBuddy = new ByteBuddy();

  /** Replaces what any call throws with its clean copy. */
  private final Advice cleanErrors = adviceOf(CleanErrorsAdvice.class);

  /** Runs a statement's execute under its time limit and span. */
  private final Advice statementLimit = adviceOf(StatementLimitAdvice.class);

  /** Runs a data source's borrow under the borrow limit. */
  private final Advice borrowLimit = adviceOf(BorrowLimitAdvice.class);

  /** Keeps the SQL of a plain execute. */
  private final Advice plainSql = Advice.to(PlainSqlAdvice.class);

  /** Keeps the SQL a connection prepares a statement with. */
  private final Advice preparedSql = Advice.to(PreparedSqlAdvice.class);

  /** Keeps the SQL added to a plain batch. */
  private final Advice batchSql = Advice.to(BatchSqlAdvice.class);

  /** The factory of the wrappers of each JDBC type other than a data source. */
  private final Map<Class<?>, BiFunction<Object, JdbcWrapper, JdbcWrapper>> factories;

  /** The generated wrapper class of a data source. */
  private final Class<? extends JdbcWrapper> dataSourceWrapper;

  /** Generates every wrapper class and its factory. */
  public JdbcWrapperClasses() {
    factories =
        WRAPPED_TYPES.stream()
            .collect(Collectors.toUnmodifiableMap(Function.identity(), this::factoryOf));
    dataSourceWrapper = wrapperOf(DataSource.class);
  }

  /**
   * Returns the factory of each wrapped JDBC type's wrappers, which takes the driver's object and
   * the wrapper whose call returned it.
   *
   * @return the factories, by JDBC type
   */
  Map<Class<?>, BiFunction<Object, JdbcWrapper, JdbcWrapper>> wrapperFactories() {
    return factories;
  }

  /**
   * Wraps a data source; called once for each data source bean at startup.
   *
   * @param dataSource the pool or data source
   * @param boundary cleans the errors and wraps the results
   * @return the wrapper
   */
  DataSource wrap(DataSource dataSource, JdbcBoundary boundary) {
    try {
      return (DataSource)
          dataSourceWrapper
              .getConstructor(Object.class, JdbcBoundary.class, JdbcWrapper.class)
              .newInstance(dataSource, boundary, null);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(
          "The generated data source wrapper cannot be created", failure);
    }
  }

  /** Generates the wrapper class of a JDBC type and the factory of its wrappers. */
  private BiFunction<Object, JdbcWrapper, JdbcWrapper> factoryOf(Class<?> type) {
    var wrapperClass = wrapperOf(type);
    var factoryType =
        TypeDescription.Generic.Builder.parameterizedType(
                BiFunction.class, Object.class, JdbcWrapper.class, JdbcWrapper.class)
            .build();
    try {
      var construct =
          MethodCall.construct(
                  wrapperClass.getConstructor(Object.class, JdbcBoundary.class, JdbcWrapper.class))
              .withArgument(0)
              .withMethodCall(
                  dynamic(
                      MethodCall.invoke(JdbcWrapper.class.getDeclaredMethod("getBoundary"))
                          .onArgument(1)))
              .withArgument(1);
      var factoryClass =
          load(
              byteBuddy
                  .subclass(factoryType)
                  .name(wrapperClass.getName() + "Factory")
                  .method(named("apply"))
                  .intercept(dynamic(construct)));
      return (BiFunction<Object, JdbcWrapper, JdbcWrapper>)
          factoryClass.getConstructor().newInstance();
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(
          "The generated factory of " + type + " cannot be created", failure);
    }
  }

  /**
   * Generates the wrapper class of a JDBC type: every method of the type goes to the delegate,
   * except those its base class implements, and a statement's {@code execute} methods run under its
   * time limit.
   */
  private Class<? extends JdbcWrapper> wrapperOf(Class<?> type) {
    var base = baseOf(type);
    DynamicType.Builder<? extends JdbcWrapper> builder =
        byteBuddy
            .subclass(base, ConstructorStrategy.Default.IMITATE_SUPER_CLASS_OPENING)
            .name(
                JdbcWrapper.class.getName()
                    + "$"
                    + type.getSimpleName()
                    + SUFFIX
                    + RandomString.make())
            .implement(type);
    for (var method : delegatedMethods(type, base)) {
      builder =
          builder
              .method(named(method.getName()).and(takesArguments(method.getParameterTypes())))
              .intercept(withAdvice(base, method, cleanErrors.wrap(callOf(method))));
    }
    return load(builder).asSubclass(JdbcWrapper.class);
  }

  /**
   * Returns a method's call with the advice its type needs: a statement's execute runs under its
   * time limit and span, after its SQL is kept when it takes one; a data source's borrow runs under
   * the borrow limit; a connection's prepare and a statement's batch keep their SQL.
   */
  private Implementation withAdvice(Class<?> base, Method method, Implementation call) {
    if (isLimited(base, method)) {
      var limited = statementLimit.wrap(call);
      return takesSql(method) ? plainSql.wrap(limited) : limited;
    }
    if (isBorrow(base, method)) {
      return borrowLimit.wrap(call);
    }
    if (base == ConnectionWrapper.class
        && method.getName().startsWith(PREPARE)
        && takesSql(method)) {
      return preparedSql.wrap(call);
    }
    if (base == StatementWrapper.class && ADD_BATCH.equals(method.getName()) && takesSql(method)) {
      return batchSql.wrap(call);
    }
    return call;
  }

  /** Returns whether a method's first parameter is its SQL. */
  private static boolean takesSql(Method method) {
    return method.getParameterCount() > 0 && method.getParameterTypes()[0] == String.class;
  }

  /** Returns the base class of a JDBC type's wrapper. */
  private Class<? extends JdbcWrapper> baseOf(Class<?> type) {
    if (Statement.class.isAssignableFrom(type)) {
      return StatementWrapper.class;
    }
    if (type == DataSource.class) {
      return DataSourceWrapper.class;
    }
    return type == Connection.class ? ConnectionWrapper.class : JdbcWrapper.class;
  }

  /** Returns whether a method runs under the statement's time limit: a statement's execute. */
  private boolean isLimited(Class<?> base, Method method) {
    return base == StatementWrapper.class && method.getName().startsWith(EXECUTE);
  }

  /** Returns whether a method borrows a connection: a data source's {@code getConnection}. */
  private boolean isBorrow(Class<?> base, Method method) {
    return base == DataSourceWrapper.class && GET_CONNECTION.equals(method.getName());
  }

  /** Returns the advice of a class, which can replace what the call throws. */
  private static Advice adviceOf(Class<?> adviceClass) {
    return Advice.withCustomMapping().with(new Advice.AssignReturned.Factory()).to(adviceClass);
  }

  /**
   * Returns the type's methods, once per signature, without those of {@link Wrapper} and those the
   * base class implements itself.
   */
  private List<Method> delegatedMethods(Class<?> type, Class<?> base) {
    return Arrays.stream(type.getMethods())
        .filter(method -> method.getDeclaringClass() != Wrapper.class)
        .filter(method -> !isImplementedBy(base, method))
        .collect(Collectors.toMap(this::signatureOf, Function.identity(), (first, second) -> first))
        .values()
        .stream()
        .toList();
  }

  /** Returns whether the base class implements a method itself, so it is not generated. */
  private boolean isImplementedBy(Class<?> base, Method method) {
    try {
      var own = base.getMethod(method.getName(), method.getParameterTypes());
      return !Modifier.isAbstract(own.getModifiers())
          && JdbcWrapper.class.isAssignableFrom(own.getDeclaringClass());
    } catch (NoSuchMethodException _) {
      return false;
    }
  }

  /** Returns a method's name and parameter types, which identify it within a type. */
  private String signatureOf(Method method) {
    return method.getName() + Arrays.toString(method.getParameterTypes());
  }

  /**
   * Returns the call of a method on the delegate, with its JDBC arguments unwrapped and its result
   * wrapped or cleaned as its return type needs.
   */
  private Implementation callOf(Method method) {
    var delegateCall = MethodCall.invoke(method).onField("delegate");
    var parameterTypes = method.getParameterTypes();
    for (var index : IntStream.range(0, parameterTypes.length).toArray()) {
      delegateCall =
          isUnwrapped(parameterTypes[index])
              ? delegateCall.withMethodCall(
                  dynamic(
                      MethodCall.invoke(declared("unwrappedArgument", Object.class))
                          .withArgument(index)))
              : delegateCall.withArgument(index);
    }
    delegateCall = dynamic(delegateCall);
    var returnType = method.getReturnType();
    if (WRAPPED_TYPES.contains(returnType)) {
      return resultOf("wrapped", delegateCall, Object.class, Class.class).with(returnType);
    }
    if (returnType == SQLWarning.class) {
      return resultOf("cleanedWarnings", delegateCall, SQLWarning.class);
    }
    if (returnType == Object.class) {
      return resultOf("wrappedObject", delegateCall, Object.class);
    }
    return delegateCall;
  }

  /** Returns whether an argument of this type is unwrapped before it reaches the driver. */
  private boolean isUnwrapped(Class<?> parameterType) {
    return parameterType == Object.class || WRAPPED_TYPES.contains(parameterType);
  }

  /** Returns the call of a {@link JdbcWrapper} method on the delegate call's result. */
  private MethodCall resultOf(String name, MethodCall delegateCall, Class<?>... parameterTypes) {
    return dynamic(MethodCall.invoke(declared(name, parameterTypes)).withMethodCall(delegateCall));
  }

  /**
   * Lets a call cast its target, arguments and result to the types the JDBC method declares, which
   * the generated code knows only at run time. ByteBuddy returns a {@code MethodCall} here, typed
   * as its interface.
   */
  private MethodCall dynamic(MethodCall call) {
    return (MethodCall) call.withAssigner(Assigner.DEFAULT, Assigner.Typing.DYNAMIC);
  }

  /** Returns a method {@link JdbcWrapper} declares for the generated code. */
  private Method declared(String name, Class<?>... parameterTypes) {
    try {
      return JdbcWrapper.class.getDeclaredMethod(name, parameterTypes);
    } catch (NoSuchMethodException failure) {
      throw new IllegalStateException("JdbcWrapper lacks " + name, failure);
    }
  }

  /** Defines a generated class next to {@link JdbcWrapper}. */
  private Class<?> load(DynamicType.Builder<?> builder) {
    try (var unloaded = builder.make()) {
      return lookup.defineClass(unloaded.getBytes());
    } catch (IllegalAccessException failure) {
      throw new IllegalStateException("The generated class cannot be defined", failure);
    }
  }
}
