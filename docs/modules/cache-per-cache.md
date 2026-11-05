# Cache: time to live and timeout per cache

**Status: Built**, in `stratum-starter-cache`, unless `stratum.cache.enabled=false`.

[← Cache](cache.md) · [Documentation home](../README.md) · Property prefix: `stratum.cache.caches`

## What it does

Every Redis cache gets Spring Boot's `spring.cache.redis.time-to-live` (10 minutes with Stratum) and
`spring.data.redis.timeout` (1 second with Stratum). One cache can need other values: prices that
change every few seconds, or large reports that take longer to read. Two keys give one cache its
own value; every other cache, and every other Redis call of the service, keeps the global one. Both
keys are listed in [Cache → Properties](cache.md#properties).

- **`stratum.cache.caches.<name>.time-to-live`**: how long an entry of that cache lives in Redis.
  Above 0.
- **`stratum.cache.caches.<name>.command-timeout`**: how long a Redis command of that cache waits
  for its answer, higher or lower than `spring.data.redis.timeout`. Above 0, and at most the larger
  of 10 seconds and `spring.data.redis.timeout`. It needs the Lettuce client, Spring Boot's default.

These apply in both modes of `stratum.backward-compatible`, because no consumer of the service sees
them.

## Set them

In `application.properties`:

```properties
stratum.cache.caches.prices.time-to-live=30s
stratum.cache.caches.reports.command-timeout=3s
stratum.cache.caches.quotes.command-timeout=200ms
```

or in `application.yml`:

```yaml
stratum:
  cache:
    caches:
      prices:
        time-to-live: 30s
      reports:
        command-timeout: 3s
      quotes:
        command-timeout: 200ms
```

In Java, set them in your `StratumCachePropertiesCustomizer`
([Cache → Configure in Java](cache.md#configure-in-java)), with
`CacheSettings.builder().timeToLive(...)` or `.commandTimeout(...)`.

## Time to live

The cache keeps the rest of its configuration: key prefix, value format and null setting come from
Spring Boot's settings, your own Spring Data `RedisCacheConfiguration` bean or your own
`RedisCacheManagerBuilderCustomizer` beans. Stratum's customizer, `stratumCacheTimeToLive`, runs
after yours, so this key wins over a time to live your own code gives the same cache. A cache with
its own time to live is created when the service starts, not on its first call.

## Command timeout

Lettuce has one command timeout for its connection. With a cache value above
`spring.data.redis.timeout`, Stratum raises that connection timeout to the largest cache value, and
gives every command its own: its cache's value, or the global one. A `RedisTemplate` call of the
service, and a cache without its own value, still times out at the global value.

- **Cost: the largest value bounds every connect.** Lettuce waits for the connection timeout while it
  connects, for all Redis use of the service. With `reports` at 3 s, a connect to a Redis that
  accepts the connection but does not answer, as during a restart, waits up to 3 s instead of 1 s.
  One line at startup says so: `Redis command timeout raised to PT3S for caches with a longer
  command-timeout; this also bounds every Redis connect handshake`. That is why a cache's value is
  at most 10 seconds; a higher one stops startup with `SX-CORE-1003`, naming the key. Raising
  `spring.data.redis.timeout` above 10 seconds lifts this limit to its own value.
- **Values count in whole milliseconds.** Lettuce counts in milliseconds, so `1500us` waits 1 ms,
  and a value below 1 ms waits 1 ms, never 0, which Lettuce reads as no timeout. With
  `spring.data.redis.timeout=0` (no timeout), other commands keep no timeout, and every cache with
  its own value counts as short.
- **`spring.data.redis.connect-timeout` stays one value for the service**, because all caches share
  one connection.
- **Clears and pings use the global value.** A clear finds keys with SCAN, which carries no key of
  the cache, so `@CacheEvict(allEntries = true)` on a large cache still waits at most
  `spring.data.redis.timeout` per SCAN call.
- **A short timeout never makes the other caches skip Redis.** When a command of a cache whose own
  value is below the global one times out, it counts as a failure of that cache
  (`stratum.cache.failures`), and the method runs. Unlike a timeout at the global value, it does not
  make every Redis cache skip Redis ([When Redis is down](cache-redis-down.md)). The one exception is
  `use-key-prefix=false` ([Limits](#limits)), where the short value never applies.

## Customize

- A bean named `stratumCacheTimeToLive` replaces Stratum's time to live customizer, a bean named
  `stratumCacheCommandTimeouts` replaces Stratum's timeouts, and a bean named
  `stratumCacheCommandTimeoutsCheck` replaces the check that warns when the client is not Lettuce.
- Your own `LettuceClientConfigurationBuilderCustomizer` and `LettuceClientOptionsBuilderCustomizer`
  beans run after Stratum's, so they win. One that sets `commandTimeout` sets how long Spring Data
  waits, so a cache value above it is cut at that value. One that sets its own `timeoutOptions`
  turns the cache values off.

## Limits

- **Other Redis clients.** With Jedis (`spring.data.redis.client-type=jedis`), the command timeouts
  of single caches are ignored, and one warning at startup names the caches. Time to live works
  with any client.
- **Your own key prefix.** The timeouts find a command's cache from its key, which must start with
  `spring.cache.redis.key-prefix` followed by `<cache>::`. A `RedisCacheConfiguration` bean or a
  `CacheKeyPrefix` of your own that writes keys another way, or `spring.cache.redis.use-key-prefix=false`,
  gives every command the global timeout. With `use-key-prefix=false`, a timeout of a cache whose own
  value is lower also counts like any other, so it makes every cache skip Redis. A cache name that
  contains `::` never matches.
- Caches other than Redis are not affected.
