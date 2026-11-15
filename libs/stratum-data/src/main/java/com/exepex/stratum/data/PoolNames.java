package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import javax.sql.DataSource;
import lombok.experimental.UtilityClass;
import org.springframework.util.ClassUtils;

/**
 * Finds a pool's one name, which its meters, its spans, the timeout counter and the startup line
 * all show: HikariCP's pool name, which is {@code main} for Spring Boot's pool and the pool's own
 * name for a named pool unless the service set another, or the data source bean's name for any
 * other data source. It loads no HikariCP type when HikariCP is not on the class path, so a service
 * that uses another pool still starts.
 */
@StratumInternal
@UtilityClass
public class PoolNames {

  /** A HikariCP class, whose presence means a data source may be a HikariCP pool. */
  private static final String HIKARI_CONFIG = "com.zaxxer.hikari.HikariConfigMXBean";

  /**
   * Returns the name of a data source bean's pool.
   *
   * @param dataSource the data source bean, wrapped or not
   * @param beanName its bean name
   * @param classLoader the application's class loader, which shows whether HikariCP is present
   * @return HikariCP's pool name when it has one, or the bean name
   */
  public static String nameOf(DataSource dataSource, String beanName, ClassLoader classLoader) {
    return ClassUtils.isPresent(HIKARI_CONFIG, classLoader)
        ? HikariPoolNames.nameOf(dataSource, beanName)
        : beanName;
  }
}
