package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.data.redis.cache.RedisCacheManager.RedisCacheManagerBuilder;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

/**
 * Has Spring Boot's Redis caches store their values as plain JSON: the defaults for every cache and
 * each cache already named in the builder, such as those of {@code spring.cache.cache-names}, which
 * Spring Boot adds before customizers run. Logs one line saying so, with the allowed packages.
 */
@StratumInternal
@Slf4j
@RequiredArgsConstructor
public class JsonValuesCustomizer implements RedisCacheManagerBuilderCustomizer {

  /** Stores and reads the JSON. */
  private final CacheJsonValues json;

  /**
   * Sets the JSON values.
   *
   * @param builder Spring Boot's builder of the Redis cache manager
   */
  @Override
  public void customize(RedisCacheManagerBuilder builder) {
    var values = json.serializationPair();
    builder.cacheDefaults(builder.cacheDefaults().serializeValuesWith(values));
    // A copy, because each cache's configuration is replaced in the map the view reads.
    Set.copyOf(builder.getConfiguredCaches())
        .forEach(name -> withJsonValues(builder, name, values));
    if (log.isInfoEnabled()) {
      var line =
          "Redis cache values are stored as JSON and read as each @Cacheable method's return type,"
              + " for types from java and the packages "
              + json.allowedPackages()
              + "; a read that names its cache manager, or skips the annotations,"
              + " gets the raw JSON";
      // Package names come from the service's own settings; SpotBugs only sees this replacement.
      log.info(line.replaceAll("[\r\n]", " "));
    }
  }

  /** Gives one cache already in the builder the JSON values, keeping its other settings. */
  private void withJsonValues(
      RedisCacheManagerBuilder builder, String name, SerializationPair<Object> values) {
    builder
        .getCacheConfigurationFor(name)
        .ifPresent(
            configuration ->
                builder.withCacheConfiguration(name, configuration.serializeValuesWith(values)));
  }
}
