package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Warns once at startup when caches set their own {@code command-timeout} but the service's Redis
 * client is not Lettuce, such as Jedis: those timeouts then do nothing. It names Lettuce's factory
 * by its class name, so it runs without Lettuce on the class path.
 */
@StratumInternal
@Slf4j
@RequiredArgsConstructor
public class CacheCommandTimeoutsCheck implements SmartInitializingSingleton {

  /** The connection to Redis. */
  private final RedisConnectionFactory connectionFactory;

  /** The caches that set their own command timeout. */
  private final Set<String> cacheNames;

  /** Warns when the timeouts are ignored; runs once every bean is created. */
  @Override
  public void afterSingletonsInstantiated() {
    if (!cacheNames.isEmpty() && !usesLettuce() && log.isWarnEnabled()) {
      var message =
          "The command-timeout of caches "
              + new TreeSet<>(cacheNames)
              + " is ignored: it needs the Lettuce Redis client, so their commands use "
              + CacheConstants.REDIS_TIMEOUT;
      // Cache names come from the service's own settings; SpotBugs only sees this replacement.
      log.warn(message.replaceAll("[\r\n]", " "));
    }
  }

  /** Returns whether the connection factory is Spring Data's Lettuce one, or extends it. */
  private boolean usesLettuce() {
    return Stream.<Class<?>>iterate(
            connectionFactory.getClass(), Objects::nonNull, Class::getSuperclass)
        .anyMatch(type -> CacheConstants.LETTUCE_CONNECTION_FACTORY.equals(type.getName()));
  }
}
