package com.exepex.stratum.cache.testsupport;

import com.exepex.stratum.cache.autoconfigure.CacheLayerAutoConfiguration;
import com.exepex.stratum.cache.autoconfigure.CaffeineCacheAutoConfiguration;
import com.exepex.stratum.cache.autoconfigure.StratumCacheAutoConfiguration;
import lombok.experimental.UtilityClass;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Contexts of a service on Spring Boot's Caffeine caches with Stratum's defaults and layer. */
@UtilityClass
public class CaffeineLayerContexts {

  /**
   * Returns a runner of Spring Boot's Caffeine caches with Stratum's defaults, its cache layer and
   * counters that record what the layer counts.
   *
   * @return the runner
   */
  public ApplicationContextRunner caffeineWithLayer() {
    return new ApplicationContextRunner()
        .withPropertyValues("spring.cache.type=caffeine")
        .withConfiguration(
            AutoConfigurations.of(
                CacheAutoConfiguration.class,
                StratumCacheAutoConfiguration.class,
                CaffeineCacheAutoConfiguration.class,
                CacheLayerAutoConfiguration.class))
        .withBean(RecordingCacheCounters.class, RecordingCacheCounters::new);
  }
}
