# Cache: `stratum-starter-cache`

**Status: Partly built.** Spring's caching switched on, so `@Cacheable` works with no
`@EnableCaching`, and safe defaults for Spring Boot's Redis cache: every entry expires, keys start
with the service's name, hits and misses are counted, Redis commands have short timeouts and
clearing a cache never blocks Redis. Cached calls keep working when Redis is down, with the method
run at most once, and the number of caches is capped. Per-cache settings, size limits, JSON values
and Caffeine are **planned** ([below](#planned)).

[← Documentation home](../README.md) · Property prefix: `stratum.cache`

## What it does

A cache that never expires grows until it fills its store. A Redis cache shared by several
services lets one service read another's entries when their keys collide. A cache that waits a
minute for a slow Redis makes every request wait with it. This starter adds Spring Boot's cache
support and gives Spring Boot's own settings safe defaults, so a service gets bounded, separated and
fast Redis caching without a line of configuration.

Stratum adds no cache of its own: Spring Boot still builds the cache manager. A **cache manager**
is the Spring object that creates and holds each named cache. Stratum only sets defaults below your
values and adds one customizer on top.

## Add it

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-starter-cache</artifactId>
</dependency>
```

To cache in Redis, also add Spring Boot's Redis starter. Its presence is what makes Spring Boot
choose Redis; the cache starter brings no Redis client of its own:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

Without it, Spring Boot picks its cache as it does today, such as Caffeine when Caffeine is on the
class path, or else its simple in-memory cache, and the Redis defaults below have no effect.

With Redis, the service needs a name, because every key starts with it. Set it in
`application.properties`:

```properties
spring.application.name=order-service
```

or in `application.yml`:

```yaml
spring:
  application:
    name: order-service
```

## Use it

```java
@Service
class ProductService {

  @Cacheable("products")
  public Product find(String sku) {
    return repository.findBySku(sku);   // runs only on a cache miss
  }

  @CacheEvict(cacheNames = "products", key = "#product.sku")
  public void update(Product product) {
    repository.save(product);
  }
}
```

## What you get

| Feature | Behaviour |
| --- | --- |
| Caching switched on | No `@EnableCaching` needed. A service or framework that has its own keeps it ([below](#your-own-enablecaching)) |
| Entries expire | Every Redis entry lives 10 minutes by default, unless you set your own time to live or [cache configuration](#customize) |
| Key prefix | Redis keys start with `<spring.application.name>:`, as in `order-service:products::sku-1` |
| Hits and misses counted | Redis caches keep statistics, so `cache.gets` reports hits and misses ([Metrics](#metrics)) |
| Short Redis timeouts | A Redis command waits at most 1 second and a connect at most 2 seconds, instead of 60 and 10 |
| Clearing with SCAN | `@CacheEvict(allEntries = true)` deletes keys found with SCAN, 1,000 at a time, never with KEYS |
| Works when Redis is down | A failed cache read is a miss and a failed write is dropped, so the method runs and returns its real result ([below](#when-redis-is-down)) |
| A cap on caches | At most 100 cache names get a cache; a name beyond that runs uncached ([Limits](#limits)) |

The beans the starter adds, with their types:

| Bean | Type | Created when |
| --- | --- | --- |
| `cacheInterceptor`, `cacheOperationSource`, `org.springframework.cache.config.internalCacheAdvisor` | `CacheInterceptor`, `CacheOperationSource`, `BeanFactoryCacheOperationSourceAdvisor` (Spring's, through `@EnableCaching`) | No `@EnableCaching` is present yet |
| `stratumScanOnClear` | `RedisCacheManagerBuilderCustomizer` | Spring Data Redis and a Redis connection are present |
| `stratumCacheLayerHook` | `StratumCacheLayerHook` | Always; it installs the layer on Spring's `cacheInterceptor` unless your own caching set-up says otherwise ([below](#when-redis-is-down)) |
| `cacheCounters` or `micrometerCacheCounters` | `CacheCounters` | Always; the Micrometer one when a `MeterRegistry` bean is present |

Spring Boot's cache auto-configuration sets up a cache manager only once caching is switched on, so
Stratum's `@EnableCaching` is ordered before it. That is the one reason this module is ordered
before a Spring Boot auto-configuration.

## Properties

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.cache.enabled` | boolean | `true` | Whether the module is on. With `false`, Stratum does not switch caching on, adds no fail-open layer or cap, and sets none of the defaults below, and Spring Boot's own apply. |
| `stratum.cache.max-caches` | integer, from 1 | `100` | The most cache names that get a cache. A name beyond it runs uncached ([Limits](#limits)). |
| `stratum.cache.warning-interval` | duration, from `1s` to `1h` | `1m` | How often one cache's warning, `SX-CACHE-1002` or `SX-CACHE-1003`, is repeated at most. |

A value out of range stops startup with `SX-CORE-1003`, which names the key.

Spring Boot properties this module gives a default (your values win):

| Spring Boot property | Type | Stratum default | Spring Boot's default | Meaning |
| --- | --- | --- | --- | --- |
| `spring.cache.redis.time-to-live` | duration | `10m` | unset: entries never expire | How long a Redis cache entry lives. |
| `spring.cache.redis.key-prefix` | string | `${spring.application.name}:` | unset: keys start with the cache name | The text every Redis cache key starts with, before `<cache>::`. See [Key prefix](#key-prefix). |
| `spring.cache.redis.enable-statistics` | boolean | `true` | `false` | Whether Redis caches count hits and misses for the metrics. |
| `spring.data.redis.timeout` | duration | `1s` | unset: Lettuce's 60 seconds | How long any Redis command waits for its answer. |
| `spring.data.redis.connect-timeout` | duration | `2s` | unset: Lettuce's 10 seconds | How long connecting to Redis may take. |

These apply in both modes of `stratum.backward-compatible`, because no consumer of the service
sees them. The two `spring.data.redis.*` timeouts apply to every Redis call of the service, a
`RedisTemplate` included, not only to caches.

Set your own values in `application.properties`:

```properties
stratum.cache.max-caches=200
stratum.cache.warning-interval=5m
spring.cache.redis.time-to-live=1h
spring.data.redis.timeout=2s
```

or in `application.yml`:

```yaml
stratum:
  cache:
    max-caches: 200
    warning-interval: 5m
spring:
  cache:
    redis:
      time-to-live: 1h
  data:
    redis:
      timeout: 2s
```

## Key prefix

Services that share one Redis must not read each other's entries. Stratum starts every key with the
service's name, so the price of `sku-1` in the cache `products` of `order-service` is stored under
`order-service:products::sku-1`.

With Spring Data Redis present, startup stops with
[`SX-CACHE-1001`](../guides/error-codes.md#stratum-starter-cache) when
`spring.application.name` is missing or blank, unless you set your own
`spring.cache.redis.key-prefix` or turn prefixes off with `spring.cache.redis.use-key-prefix=false`.
The check runs before any bean exists, so in three cases it asks for a name that your prefix does
not use: when `spring.cache.type` names another cache than `redis`, when you declare your own Spring
Data `RedisCacheConfiguration` bean, and when you declare your own `CacheManager` bean. Set
`spring.application.name` or `spring.cache.redis.key-prefix` in those cases.

## Connect to Redis

The connection uses Spring Boot's own `spring.data.redis.*` properties, TLS and authentication
included. Take the password from the environment, never from a file in the repository. In
`application.properties`:

```properties
spring.data.redis.host=redis.internal
spring.data.redis.port=6379
spring.data.redis.username=order-service
spring.data.redis.password=${REDIS_PASSWORD}
spring.data.redis.ssl.enabled=true
```

or in `application.yml`:

```yaml
spring:
  data:
    redis:
      host: redis.internal
      port: 6379
      username: order-service
      password: ${REDIS_PASSWORD}
      ssl:
        enabled: true
```

To trust your own certificate authority, name a Spring Boot SSL bundle with
`spring.data.redis.ssl.bundle` instead of `ssl.enabled`.

## Clearing a cache

`@CacheEvict(allEntries = true)` deletes every entry of a cache. Spring Boot's writer finds the keys
with KEYS, which blocks Redis for every client while it walks the whole keyspace. Stratum's
`stratumScanOnClear` customizer gives Spring Boot's cache manager a writer that finds them with
SCAN instead, asking Redis for 1,000 keys per call, so Redis keeps serving other commands between
calls. The writer takes no lock, as Spring Boot's does, and keeps the statistics setting.

Like Spring Boot's writer, it writes and clears in the background, as Spring Data Redis does with
Lettuce: right after a cached method or a clear returns, Redis may not hold the change yet, and an
entry may still be read for a moment. With `@CacheEvict(allEntries = true, beforeInvocation = true)`,
Spring clears the cache at once, before the method runs, also with SCAN.

## When Redis is down

A cached method keeps working while Redis is down or slow. A failed cache read counts as a miss, so
the method runs; a failed write is dropped; and the call returns the method's real result. The
method's own exception reaches the caller unchanged, as without caching.

- **Cost.** Each call still waits for Redis first: about 1 second with `@Cacheable(sync = true)`
  and about 2 seconds without it, at the 1 s default of `spring.data.redis.timeout`. Without
  `sync`, the write after the method is sent in the background, but it still waits for the broken
  connection. Under load the reconnects on the shared connection raise this, to about 6 seconds a
  call at ten threads at once. A gate that skips Redis while it is unreachable is **planned**.
- **The method runs once.** With `sync = true`, when the write after a load fails, the loaded value
  is returned and the method is not run a second time.
- **Evictions are dropped too.** A `@CacheEvict` that fails during an outage leaves the old entry in
  Redis, so it can be read after Redis returns, until its time to live ends.
- **Counted and warned.** Each failure is counted in `stratum.cache.failures`, and one
  [`SX-CACHE-1002`](../guides/error-codes.md#stratum-starter-cache) warning per cache is logged per
  `stratum.cache.warning-interval`. It names the cache and the operation, never a key or a value.
- **Your own handler wins.** A `CachingConfigurer` of yours with its own `errorHandler()` sees every
  cache error. If it rethrows, the call fails, as you chose. A handler that is Spring's own
  `SimpleCacheErrorHandler` counts as no handler, so write your own one that rethrows to keep calls
  failing.

What skips parts of the layer:

- A `CachingConfigurer` of yours that sets a `cacheResolver()` or a `cacheManager()`: Stratum then
  adds nothing, and Spring's behaviour applies.
- `@Cacheable(cacheManager = ...)` or `cacheResolver = ...` on a method: its calls are still
  fail-open, but they get no run-once loader and no cap.
- Your code calling `CacheManager.getCache(...)` itself, and `@EnableCaching(mode =
  AdviceMode.ASPECTJ)`, which has no `cacheInterceptor`: neither gets the layer.

## Metrics

With Micrometer present, as with the actuator or the
[observability starter](../../starters/stratum-starter-observability/README.md), Spring Boot
reports `cache.gets` (with `result=hit` or `miss`), `cache.puts` and `cache.removals` for each
cache. Spring Boot measures only the caches the cache manager knows at startup, so name them:

```properties
spring.cache.cache-names=products,exchange-rates
```

```yaml
spring:
  cache:
    cache-names: products,exchange-rates
```

A cache made later, on its first use, is not measured; measuring those is **planned**. Redis reports
no evictions.

Stratum adds two counters of its own:

| Metric | Tags | Counts |
| --- | --- | --- |
| `stratum.cache.failures` | `cache`, `operation` (`get`, `put`, `evict`, `clear`) | Cache calls that failed and were handled fail-open ([When Redis is down](#when-redis-is-down)) |
| `stratum.cache.skips` | `cache` (`_over-limit`), `reason` (`cache-limit`) | Calls for a cache name over `stratum.cache.max-caches` ([Limits](#limits)) |

## An existing service

Adding the starter to a service that already caches in Redis changes this:

- The 1 s and 2 s timeouts reach every Redis call of the service, `RedisTemplate` included.
- Entries expire after 10 minutes by default, where they may have lived for ever.
- The new key prefix leaves the old keys unread. Delete them with SCAN by pattern, never with
  `FLUSHDB` on a Redis shared with other data; see
  [Migrating a service, step 7](../guides/migrating-a-service.md#step-7-data-cache-kafka).
- `SX-CACHE-1001` stops startup when the service has no name ([Key prefix](#key-prefix)).
- During a Redis outage a cached method now runs and returns its result, where the call used to
  fail ([When Redis is down](#when-redis-is-down)).

Each can be set back with the Spring Boot property in the [table above](#properties).

## Configure in Java

Every `stratum.cache.*` setting can also be set in Java, through a
`StratumCachePropertiesCustomizer` class, and Java wins over the files:

```java
public class OrdersCacheSettings implements StratumCachePropertiesCustomizer {

  @Override
  public StratumCacheProperties customize(StratumCacheProperties properties) {
    return properties.toBuilder().maxCaches(200).warningInterval(Duration.ofMinutes(5)).build();
  }
}
```

The customizer is not a bean: list the class in the service's `META-INF/spring.factories`. A
customizer declared as a bean stops startup with `SX-CORE-1004`.

```properties
com.exepex.stratum.cache.StratumCachePropertiesCustomizer=com.acme.orders.OrdersCacheSettings
```

Set any Spring Boot key above in Java as a default property, which wins over Stratum's default:

```java
var application = new SpringApplication(OrdersApplication.class);
application.setDefaultProperties(Map.of("spring.cache.redis.time-to-live", "1h"));
application.run(args);
```

## Customize

- Every `spring.cache.*` and `spring.data.redis.*` property works and wins.
- Your own `RedisCacheManagerBuilderCustomizer` or `CacheManagerCustomizer` beans run after
  Stratum's customizer, which has `@Order(Ordered.HIGHEST_PRECEDENCE)`, so they win. A customizer of
  yours with that same order runs first, and Stratum's then replaces its writer; give yours a later
  order, or name it `stratumScanOnClear` to replace Stratum's.
- A bean named `stratumScanOnClear` replaces Stratum's customizer. Note: it backs off by its name,
  not by its type, as most beans do ([Overriding defaults, level 3](../configuration/overriding-defaults.md#level-3-replace-a-bean)),
  because a customizer of yours of the same type must not switch SCAN off.
- Your own Spring Data `RedisCacheConfiguration` bean replaces Spring Boot's cache settings, and so
  Stratum's time to live and key prefix; the SCAN writer still applies.
- Your own `CacheManager` bean replaces Spring Boot's, and with it Stratum's time to live, key
  prefix, statistics and SCAN clears. Stratum's `@EnableCaching`, the Redis timeouts and the
  [name check](#key-prefix) still apply.

### Your own `@EnableCaching`

A service or company framework that declares `@EnableCaching` keeps it: Stratum's stands back,
and the defaults above still apply. This holds for `@EnableCaching(mode = AdviceMode.ASPECTJ)` in
the service too. A company auto-configuration with its own `@EnableCaching` that Spring Boot orders
after its cache auto-configuration, and that is not ordered before it, does not make Stratum's
stand back. In proxy mode the two still give one caching layer. In AspectJ mode, Stratum's proxy is
added beside the company's aspect; with AspectJ weaving, a cached call may then be checked, stored
or evicted twice, which is not verified. Declare `@EnableCaching(mode = AdviceMode.ASPECTJ)` in
the service, or set `stratum.cache.enabled=false`, which also drops every default on this page.

## Switch it on or off

The starter is on when it is on the classpath. Leaving `stratum.cache.enabled` unset, or empty, is
the same as `true`.

`stratum.cache.enabled=false` turns off Stratum's `@EnableCaching` and its cache and Redis
defaults: time to live, key prefix, statistics, timeouts, clearing with SCAN, the fail-open layer
and the cap on caches. A service with its
own `@EnableCaching` keeps caching, with Spring Boot's defaults; without one, `@Cacheable` methods
then always run.

## Limits

- Other cache types than Redis, such as Spring Boot's simple in-memory cache, get Spring Boot's
  behaviour only.
- Redis memory is bounded by the server's `maxmemory` policy, which your team sets.
- Redis reports no evictions to the metrics.
- At most `stratum.cache.max-caches` cache names, 100 by default, get a cache. Calls for a name
  beyond that run uncached, through one shared no-op cache, and no cache is created for the name.
  Each such call is counted in `stratum.cache.skips` under `cache=_over-limit`, and
  [`SX-CACHE-1003`](../guides/error-codes.md#stratum-starter-cache) is logged at most once per
  `stratum.cache.warning-interval`.

### Planned

These are planned for later releases of this module: null results not cached; metrics
for caches made on first use; a time to live, command timeout and size limits for each cache;
values stored as JSON, read only for allowed types; a retry interval while Redis is unreachable;
the cache name on Redis spans; Caffeine with a bounded size; and reactive return types.

Not planned for this release: a two-level cache, several regions, keys per tenant, other cache
clients than Redis and Caffeine, distributed locks, spans for hits and misses, Spring Session,
Redis data structures and search indexes, work specific to Sentinel or Cluster (Spring Boot's
`spring.data.redis.sentinel.*`, `cluster.*` and `lettuce.read-from` keys suffice), and a lock or
jittered time to live per instance.

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| The method always runs | Called from the same class, so the call bypasses Spring's proxy, or `stratum.cache.enabled=false` with no `@EnableCaching` of your own. |
| Startup stops with `SX-CACHE-1001` | Spring Data Redis is present and the service has no name. Set `spring.application.name` or your own `spring.cache.redis.key-prefix` ([Key prefix](#key-prefix)). |
| Stale data after an update | Add `@CacheEvict` or `@CachePut` to the method that changes the data, or lower the time to live. |
| Hit ratio near zero | The key includes a value that changes on every call (for example a timestamp). Set `key` explicitly. |
| `cache.gets` is missing for a cache | The cache was made on its first use. List it in `spring.cache.cache-names` ([Metrics](#metrics)). |
| Redis calls time out after 1 second | A command takes longer than Stratum's `spring.data.redis.timeout`. Raise it for the whole service. |
| `SX-CACHE-1002` in the log | A cache call failed, usually because Redis is down; the method still ran. Check Redis ([When Redis is down](#when-redis-is-down)). |
| `SX-CACHE-1003` in the log | The service uses more than `stratum.cache.max-caches` cache names. Name caches explicitly, so they stay few, or raise the limit ([Limits](#limits)). |
