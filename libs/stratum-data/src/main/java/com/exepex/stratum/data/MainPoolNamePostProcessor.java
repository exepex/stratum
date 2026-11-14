package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/**
 * Names Spring Boot's HikariCP pool {@code main} when the service gave it no name, so its meters,
 * spans, timeout counter and startup line show {@code main} rather than {@code HikariPool-1}. A
 * name the service set through {@code spring.datasource.name} or {@code
 * spring.datasource.hikari.pool-name} is kept. Meters are internal, so this holds with {@code
 * stratum.backward-compatible=true} too.
 */
@StratumInternal
public final class MainPoolNamePostProcessor implements BeanPostProcessor, Ordered {

  /** Runs before the login limit, at -200, and the JDBC boundary, at -100, read the name. */
  private static final int ORDER = -300;

  /**
   * Names Spring Boot's pool {@code main} when it has no name yet; HikariCP names a pool only when
   * it starts, so a pool without a name has not started and accepts it.
   *
   * @param bean the bean, after its initialisation and after Spring Boot bound its settings
   * @param beanName its name
   * @return the bean itself
   */
  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (DataConstants.MAIN_BEAN.equals(beanName)
        && bean instanceof HikariDataSource
        && ((HikariConfig) bean).getPoolName() == null) {
      ((HikariConfig) bean).setPoolName(DataConstants.MAIN_POOL);
    }
    return bean;
  }

  /**
   * Runs before the other post-processors that read the pool's name.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return ORDER;
  }
}
