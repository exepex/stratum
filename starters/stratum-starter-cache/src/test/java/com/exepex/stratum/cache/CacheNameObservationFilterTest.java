package com.exepex.stratum.cache;

import static com.exepex.stratum.cache.testsupport.RedisCommandTestData.get;
import static com.exepex.stratum.cache.testsupport.RedisCommandTestData.ping;
import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.protocol.RedisCommand;
import io.lettuce.core.tracing.LettuceObservationContext;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CacheNameObservationFilter puts the cache name on Redis command spans")
class CacheNameObservationFilterTest {

  private final CacheNameObservationFilter filter =
      new CacheNameObservationFilter(new RedisCacheNames("orders:", List.of("products")));

  /** Returns the observation of a Redis command, as Lettuce makes it. */
  private LettuceObservationContext observed(RedisCommand<?, ?, ?> command) {
    var context = new LettuceObservationContext("Redis");
    context.setCommand(command);
    return context;
  }

  @Test
  @DisplayName("a command of a known cache gets cache.name as a high-cardinality value only")
  void namesKnownCache() {
    var context = filter.map(observed(get("orders:products::sku-1")));

    assertThat(context.getHighCardinalityKeyValues())
        .containsExactly(KeyValue.of(CacheConstants.SPAN_CACHE_NAME, "products"));
    assertThat(context.getLowCardinalityKeyValues()).isEqualTo(KeyValues.empty());
    assertThat(context.getHighCardinalityKeyValues().stream().map(KeyValue::getValue))
        .noneMatch(value -> value.contains("sku-1"));
  }

  @Test
  @DisplayName("a key of an unknown cache, of the service's own, or no key adds nothing")
  void unknownAddsNothing() {
    var unknown = filter.map(observed(get("orders:rates::EUR")));
    var ownKey = filter.map(observed(get("orders:42::lines")));
    var keyless = filter.map(observed(ping()));
    var noCommand = filter.map(new LettuceObservationContext("Redis"));

    assertThat(List.of(unknown, ownKey, keyless, noCommand))
        .allSatisfy(context -> assertThat(context.getAllKeyValues()).isEmpty());
  }

  @Test
  @DisplayName("an observation that is not a Redis command is passed on untouched")
  void otherObservationsUntouched() {
    var other = new Observation.Context();

    assertThat(filter.map(other)).isSameAs(other);
    assertThat(other.getAllKeyValues()).isEmpty();
  }
}
