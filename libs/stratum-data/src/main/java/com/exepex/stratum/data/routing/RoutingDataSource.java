package com.exepex.stratum.data.routing;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.PoolRouter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Hands out a connection of the pool the current call wants, and checks each statement on it
 * against that pool. It answers {@code unwrap} only for itself, so Spring Boot's pool metrics never
 * count main's pool a second time under its name.
 */
@StratumInternal
public final class RoutingDataSource extends AbstractRoutingDataSource implements PoolRouter {

  /** The pool each call wants. */
  private final PoolRouting routing;

  /**
   * Creates the routing over the named pools and {@code main}. A key it does not hold fails rather
   * than falling back to main, so no statement silently reaches the wrong database.
   *
   * @param routing the pool each call wants
   * @param pools each mapped named pool's data source, by the pool's name
   * @param main main's data source
   */
  public RoutingDataSource(PoolRouting routing, Map<String, DataSource> pools, DataSource main) {
    this.routing = routing;
    var targets = new HashMap<Object, Object>(pools);
    targets.put(DataConstants.MAIN_POOL, main);
    setTargetDataSources(targets);
    setDefaultTargetDataSource(main);
    setLenientFallback(false);
    afterPropertiesSet();
  }

  @Override
  protected Object determineCurrentLookupKey() {
    return routing.currentPool();
  }

  @Override
  public Connection getConnection() throws SQLException {
    var pool = routing.currentPool();
    return checked(super.getConnection(), pool);
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    var pool = routing.currentPool();
    return checked(super.getConnection(username, password), pool);
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return iface.isInstance(this);
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLException("The routing data source wraps no " + iface.getName());
  }

  /** Returns the connection with each statement checked against the pool it was opened on. */
  private Connection checked(Connection connection, String pool) {
    return CheckedConnection.builder().connection(connection).pool(pool).routing(routing).build();
  }
}
