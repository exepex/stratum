package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.experimental.UtilityClass;

/** Tells whether a data source bean is Stratum's routing data source, or wraps it. */
@StratumInternal
@UtilityClass
public class PoolRouters {

  /**
   * Returns whether a data source is, or wraps, Stratum's routing data source.
   *
   * @param dataSource the data source
   * @return whether it routes to the pools
   */
  public static boolean routes(DataSource dataSource) {
    try {
      return dataSource instanceof PoolRouter || dataSource.isWrapperFor(PoolRouter.class);
    } catch (SQLException failure) {
      return false;
    }
  }
}
