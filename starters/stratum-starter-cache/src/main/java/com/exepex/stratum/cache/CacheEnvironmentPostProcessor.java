package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.core.properties.StratumDefaults;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Gives Spring Boot's Redis cache and client settings Stratum's defaults unless {@code
 * stratum.cache.enabled=false}: entries live 10 minutes, keys start with the service's name, hits
 * and misses are counted, a command waits at most 1 second and a connect at most 2 seconds. Every
 * value the service sets for these Spring Boot keys wins. In both modes, because no consumer of the
 * service sees them.
 */
@StratumInternal
public class CacheEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** Stratum's defaults for Spring Boot's keys. */
  private static final Map<String, Object> DEFAULTS =
      Map.of(
          CacheConstants.TIME_TO_LIVE,
          "10m",
          CacheConstants.KEY_PREFIX,
          CacheConstants.SERVICE_NAME_PREFIX,
          CacheConstants.ENABLE_STATISTICS,
          true,
          CacheConstants.REDIS_TIMEOUT,
          "1s",
          CacheConstants.REDIS_CONNECT_TIMEOUT,
          "2s");

  /**
   * Runs right after the configuration files are read, so that the module switch and the service's
   * values are known.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 1;
  }

  /**
   * Checks that Stratum's key prefix has a service name to use, then adds the defaults below the
   * service's own values, if the module is on.
   *
   * @param environment the application's environment
   * @param application the application being started, whose class loader shows Spring Data Redis
   * @throws StratumException {@code SX-CACHE-1001} if Spring Data Redis is present and Stratum's
   *     key prefix would be used, but the service has no name
   */
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (!StratumModuleSwitch.isEnabled(environment, CacheConstants.MODULE)) {
      return;
    }
    if (ClassUtils.isPresent(CacheConstants.SPRING_DATA_REDIS, application.getClassLoader())
        && usesStratumKeyPrefix(environment)
        && !hasServiceName(environment)) {
      throw new StratumException(CacheErrorCode.APPLICATION_NAME_MISSING);
    }
    StratumDefaults.addBelowServiceValues(environment, DEFAULTS);
  }

  /**
   * Returns whether Redis cache keys get Stratum's prefix: keys use one and the service sets none.
   */
  private boolean usesStratumKeyPrefix(ConfigurableEnvironment environment) {
    var binder = Binder.get(environment);
    // Spring's Boolean return is nullable for SpotBugs, so the comparison avoids unboxing it.
    return Boolean.TRUE.equals(
            binder.bind(CacheConstants.USE_KEY_PREFIX, Boolean.class).orElse(true))
        && !binder.bind(CacheConstants.KEY_PREFIX, String.class).isBound();
  }

  /** Returns whether the service has a name that is not blank. */
  private boolean hasServiceName(ConfigurableEnvironment environment) {
    return StringUtils.hasText(
        Binder.get(environment).bind(CacheConstants.APPLICATION_NAME, String.class).orElse(null));
  }
}
