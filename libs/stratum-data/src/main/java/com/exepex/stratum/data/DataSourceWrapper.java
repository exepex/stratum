package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/**
 * The base of the boundary's data source wrapper. On a data source with statement limits, it fails
 * a borrow before the pool is asked when the web request it serves has no time left. Its subclass
 * is generated at startup.
 */
@StratumInternal
public abstract class DataSourceWrapper extends JdbcWrapper {

  /**
   * Wraps a data source.
   *
   * @param delegate the pool or data source
   * @param boundary the boundary of the data source
   * @param parent none: a data source is the first object wrapped
   */
  protected DataSourceWrapper(
      Object delegate, JdbcBoundary boundary, @Nullable JdbcWrapper parent) {
    super(delegate, boundary, parent);
  }

  /**
   * Checks the time left before a borrow, for the generated code.
   *
   * @throws SQLException {@code SX-DATA-1003} when the request has no time left; the pool is then
   *     not asked
   */
  void enterBorrow() throws SQLException {
    var limits = boundary.getLimits();
    if (limits != null) {
      limits.checkBorrow(false);
    }
  }
}
