package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.zaxxer.hikari.HikariConfigMXBean;
import javax.sql.DataSource;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.jdbc.DataSourceUnwrapper;

/**
 * Finds a pool's one name, which its meters, its spans, the timeout counter and the startup line
 * all show: HikariCP's pool name, which is {@code main} for Spring Boot's pool and the pool's own
 * name for a named pool unless the service set another, or the data source bean's name for any
 * other data source.
 */
@StratumInternal
@UtilityClass
public class PoolNames {

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
