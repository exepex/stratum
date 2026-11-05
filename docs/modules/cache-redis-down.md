# Cache: when Redis is down

**Status: Built**, in `stratum-starter-cache`, unless `stratum.cache.enabled=false`.

[← Cache](cache.md) · [Documentation home](../README.md) · Property prefix: `stratum.cache`

## What it does

A cached method keeps working while Redis is down or slow. A failed cache read counts as a miss, so
the method runs; a failed write is dropped; and the call returns the method's real result. The
method's own exception reaches the caller unchanged, as without caching.

- **Redis is skipped for a while.** When `stratum.cache.redis-failure-threshold` calls in a row (3
  by default) cannot reach Redis, are refused because the connection is down, or wait longer than
  `spring.data.redis.timeout`, every Redis cache of the service skips Redis (a cache whose own [command timeout](cache-per-cache.md#command-timeout)
  is lower does not cause it, apart from the [exception](cache-per-cache.md#limits) of
  `use-key-prefix=false`) for `stratum.cache.redis-retry-interval` (5 seconds by default). A call
  that reaches Redis starts the count again, so one slow command alone does not cause it. Reads
  then miss at once and writes and evictions are dropped, without a call to Redis. Then one call
  tries Redis again while the others keep skipping it; when it gets an answer, every call uses Redis
  again, and when it fails, Redis is skipped again at once. A write or evict that tries first deletes its key at once, since a write in the background
  cannot tell whether Redis answered. Other caches, such as an in-memory one, never skip.
- **Cost.** Only the calls already waiting on Redis when it fails, and at most 3 calls in a row,
  wait for its 1 s timeout; after that, one call per instance per 5 seconds does. While Redis is
  skipped, every Redis cache misses, so that load goes to your database or downstream service.
  Lower `stratum.cache.redis-retry-interval` if it cannot take that. A Redis that times out on some
  calls but answers others stays in use, and each timed-out call waits the full timeout. Set
  `stratum.cache.redis-failure-threshold=1` to skip Redis at the first failure.
- **At startup**, until the first call has reached Redis, only that call tries it, so the others
  do not queue behind the first connect; they miss the cache for that short time.
- **Lettuce refuses commands while disconnected.** Stratum sets Lettuce's `REJECT_COMMANDS`, so any
  Redis command of the service, a `RedisTemplate`, a session or a lock included, fails at once
  while the connection is down. Lettuce's own default holds such commands and sends them once it
  has reconnected, so a command sent during a short blip used to succeed. To keep that, declare
  your own `LettuceClientOptionsBuilderCustomizer` bean: it runs after Stratum's and wins.
- **The method runs once.** With `sync = true`, when the write after a load fails, the loaded value
  is returned and the method is not run a second time.
- **Evictions are dropped too.** A `@CacheEvict` that fails or is skipped during an outage leaves
  the old entry in Redis, so it can be read after Redis returns, until its time to live ends.
- **Counted and warned.** Each failure is counted in `stratum.cache.failures`, and one
  [`SX-CACHE-1002`](../guides/error-codes.md#stratum-starter-cache) warning per cache is logged per
  `stratum.cache.warning-interval`. It names the cache and the operation, never a key or a value.
  When Redis starts being skipped, [`SX-CACHE-1006`](../guides/error-codes.md#stratum-starter-cache)
  is logged, at most once per `stratum.cache.warning-interval`. For alerts, use the gauge
  `stratum.cache.redis.available` and the counter `stratum.cache.redis.connect-failures`
  ([Metrics](cache.md#metrics)).
- **Your own handler wins.** A `CachingConfigurer` of yours with its own `errorHandler()` sees every
  cache error. If it rethrows, the call fails, as you chose. A handler that is exactly Spring's
  `SimpleCacheErrorHandler` counts as no handler; a subclass of it counts as yours. To keep calls
  failing, set your own handler that rethrows. Calls skipped while Redis is skipped do not fail, so
  your handler does not see them.

What skips parts of the layer:

- A `CachingConfigurer` of yours that sets a `cacheResolver()` or a `cacheManager()`: Stratum then
  adds nothing, and Spring's behaviour applies.
- `@Cacheable(cacheManager = ...)` or `cacheResolver = ...` on a method: its calls are still
  fail-open, but they get no run-once loader, no cap, no [null skip](cache-null-results.md), no
  [metrics](cache.md#metrics) for a cache made on its first use, and never skip Redis.
- Your code calling `CacheManager.getCache(...)` itself, and `@EnableCaching(mode =
  AdviceMode.ASPECTJ)`, which has no `cacheInterceptor`: neither gets the layer.
