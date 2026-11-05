package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import io.lettuce.core.tracing.LettuceObservationContext;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Puts {@code cache.name} on the span of each Redis command whose key belongs to a known cache. It
 * is a high-cardinality value, so it never becomes a metric tag, and the key itself is never added.
 */
@StratumInternal
@RequiredArgsConstructor
public class CacheNameObservationFilter implements ObservationFilter {

  /** The cache names a key can belong to. */
  private final RedisCacheNames cacheNames;

  /**
   * Adds the cache name to a Redis command's observation when its key belongs to a known cache;
   * leaves every other observation as it is.
   *
   * @param context the observation, when it stops
   * @return the same context
   */
  @Override
  public Observation.Context map(Observation.Context context) {
    if (context instanceof LettuceObservationContext lettuce) {
      var name = cacheNameOf(lettuce);
      if (name != null) {
        lettuce.addHighCardinalityKeyValue(KeyValue.of(CacheConstants.SPAN_CACHE_NAME, name));
      }
    }
    return context;
  }

  /** Returns the known cache of the command's first key, or {@code null}. */
  private @Nullable String cacheNameOf(LettuceObservationContext lettuce) {
    var args = lettuce.hasCommand() ? lettuce.getRequiredCommand().getArgs() : null;
    return args == null ? null : cacheNames.cacheNameOf(args.getFirstEncodedKey());
  }
}
