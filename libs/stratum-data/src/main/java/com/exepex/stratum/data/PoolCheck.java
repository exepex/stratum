package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import com.zaxxer.hikari.HikariConfigMXBean;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.jdbc.DataSourceUnwrapper;
import org.springframework.core.env.Environment;

/**
 * Checks every pool once all beans exist and before the application is ready, so before the TLS
 * check borrows its first connection: no two pools share a name, and their {@code
 * maximum-pool-size} values add up to no more than {@code stratum.data.max-connections}. It logs
 * one line per pool with its name and maximum, never its URL or credentials. A data source that is
 * no HikariCP pool counts 0, and its line says so.
 */
@Slf4j
@StratumInternal
@RequiredArgsConstructor
public final class PoolCheck implements SmartInitializingSingleton {

  /** HikariCP's maximum when nothing sets one. */
  private static final int HIKARI_MAXIMUM_POOL_SIZE = 10;

  /** Finds the data source beans. */
  private final ListableBeanFactory beanFactory;

  /** The application's environment, with the cap. */
  private final Environment environment;

  /**
   * Checks the pools and logs each one.
   *
   * @throws StratumException {@code SX-DATA-1004} if two pools share a name, or {@code
   *     SX-DATA-1005} if the pools may open more connections than the cap
   */
  @Override
  public void afterSingletonsInstantiated() {
    var maximums = maximumsByPool();
    int cap = DataProperties.resolve(environment).maxConnectionsOrStandard();
    int total = maximums.values().stream().mapToInt(Integer::intValue).sum();
    if (total > cap) {
      throw new StratumException(
          DataErrorCode.TOO_MANY_CONNECTIONS,
          "%s is %d, but the pools may open %d connections: %s"
              .formatted(
                  DataConstants.MAX_CONNECTIONS,
                  cap,
                  total,
                  LogLines.oneLine(maximums.toString())));
    }
  }

  /**
   * Returns each pool's maximum by its name, each pool once however many beans hand it out, and
   * logs it.
   */
  private Map<String, Integer> maximumsByPool() {
    var maximums = new LinkedHashMap<String, Integer>();
    Set<HikariConfigMXBean> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    beanFactory
        .getBeansOfType(DataSource.class, false, false)
        .forEach((beanName, dataSource) -> add(maximums, seen, beanName, dataSource));
    return maximums;
  }

  /**
   * Adds one data source bean's pool, unless it was seen or is Stratum's routing data source, whose
   * pools are beans of their own, and logs it.
   */
  private void add(
      Map<String, Integer> maximums,
      Set<HikariConfigMXBean> seen,
      String beanName,
      DataSource dataSource) {
    if (PoolRouters.routes(dataSource)) {
      return;
    }
    var pool = DataSourceUnwrapper.unwrap(dataSource, HikariConfigMXBean.class);
    if (pool != null && !seen.add(pool)) {
      return;
    }
    var name = HikariPoolNames.nameOf(pool, beanName);
    if (maximums.containsKey(name)) {
      throw new StratumException(
          DataErrorCode.POOL_SETTING_INVALID,
          "two pools are named %s, so their meters would be mixed"
              .formatted(LogLines.oneLine(name)));
    }
    int maximum = maximumOf(pool);
    maximums.put(name, maximum);
    logPool(name, pool, maximum);
  }

  /** Returns a pool's maximum, HikariCP's own when nothing set one, or 0 for no HikariCP pool. */
  private int maximumOf(@Nullable HikariConfigMXBean pool) {
    if (pool == null) {
      return 0;
    }
    return pool.getMaximumPoolSize() > 0 ? pool.getMaximumPoolSize() : HIKARI_MAXIMUM_POOL_SIZE;
  }

  /** Logs a pool's name and maximum, or that a data source is no HikariCP pool. */
  private void logPool(String name, @Nullable HikariConfigMXBean pool, int maximum) {
    if (!log.isInfoEnabled()) {
      return;
    }
    if (pool == null) {
      log.info(
          "Data source '{}' is not a HikariCP pool, so it is not counted", LogLines.oneLine(name));
    } else {
      log.info("Pool '{}' opens at most {} connections", LogLines.oneLine(name), maximum);
    }
  }
}
