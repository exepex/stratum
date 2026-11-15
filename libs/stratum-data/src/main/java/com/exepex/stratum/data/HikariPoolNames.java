package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.zaxxer.hikari.HikariConfigMXBean;
import javax.sql.DataSource;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.jdbc.DataSourceUnwrapper;

/**
 * Reads a HikariCP pool's name. Only code that runs when HikariCP is on the class path calls it;
 * every other caller goes through {@link PoolNames}, which never loads a HikariCP type.
 */
@StratumInternal
@UtilityClass
public class HikariPoolNames {

  /**
   * Returns the name of a data source bean's pool.
   *
   * @param dataSource the data source bean, wrapped or not
   * @param beanName its bean name
   * @return HikariCP's pool name when it has one, or the bean name
   */
  public static String nameOf(DataSource dataSource, String beanName) {
    return nameOf(DataSourceUnwrapper.unwrap(dataSource, HikariConfigMXBean.class), beanName);
  }

  /**
   * Returns the name of a HikariCP pool.
   *
   * @param pool the pool's settings, or {@code null} for a data source that is no HikariCP pool
   * @param beanName the data source bean's name
   * @return HikariCP's pool name when it has one, or the bean name
   */
  public static String nameOf(@Nullable HikariConfigMXBean pool, String beanName) {
    var name = pool == null ? null : pool.getPoolName();
    return name == null ? beanName : name;
  }
}
