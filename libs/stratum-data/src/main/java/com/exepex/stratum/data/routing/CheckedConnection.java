package com.exepex.stratum.data.routing;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Set;
import lombok.Builder;

/**
 * Checks, before each statement is created, that the current call wants the pool the connection was
 * opened on, so a transaction never reads or writes another pool than its own. Two checked
 * connections are equal when they hold the same pool connection, opened on the same pool.
 *
 * @param connection the pool's connection
 * @param pool the pool it was opened on
 * @param routing the pool each call wants
 */
@Builder
record CheckedConnection(Connection connection, String pool, PoolRouting routing)
    implements InvocationHandler {

  /** The methods that create a statement. */
  private static final Set<String> STATEMENT_METHODS =
      Set.of("createStatement", "prepareStatement", "prepareCall");

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    if ("equals".equals(method.getName()) && args != null && args.length == 1) {
      return args[0] != null
          && Proxy.isProxyClass(args[0].getClass())
          && equals(Proxy.getInvocationHandler(args[0]));
    }
    if ("hashCode".equals(method.getName()) && args == null) {
      return hashCode();
    }
    if (STATEMENT_METHODS.contains(method.getName())) {
      routing.checkStatement(pool);
    }
    try {
      return method.invoke(connection, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }
}
