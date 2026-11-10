# Cache: `stratum-starter-cache`

**Status: Built.** Spring's caching switched on, so `@Cacheable` works with no
`@EnableCaching`, and safe defaults for Spring Boot's Redis cache: every entry expires, keys start
with the service's name, hits and misses are counted, Redis commands have short timeouts and
clearing a cache never blocks Redis. Cached calls keep working when Redis is down, with the method
run at most once, skip Redis for a few seconds after it fails, the number of caches is capped, and
keys and values have size limits, also per cache, and values are stored as plain JSON, read only as
allowed types ([Stored values](cache-values.md)). Each cache can have its own time to live and
command timeout ([per cache](cache-per-cache.md)). Caffeine caches are bounded to 10,000 entries
that live 10 minutes ([Caffeine](cache-caffeine.md)).

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
  <groupId>io.github.exepex.stratum</groupId>
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

Without it, Spring Boot picks Caffeine, which the cache starter brings, with Stratum's
[Caffeine defaults](cache-caffeine.md), and the Redis defaults below have no effect. With it,
Spring Boot picks Redis before Caffeine ([Caffeine](cache-caffeine.md#use-it)).

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
| Hits and misses counted | Redis caches keep statistics, so `cache.gets` reports hits and misses, also for caches made on their first use ([Metrics](#metrics)) |
| Null results not stored | A method that returns `null` is not cached, so it runs again on the next call; no `unless` needed ([Null results](cache-null-results.md)) |
| Short Redis timeouts | A Redis command waits at most 1 second and a connect at most 2 seconds, instead of 60 and 10 |
| Clearing with SCAN | `@CacheEvict(allEntries = true)` deletes keys found with SCAN, 1,000 at a time, never with KEYS |
| Works when Redis is down | A failed cache read is a miss and a failed write is dropped, so the method runs and returns its real result ([below](cache-redis-down.md)) |
| A cap on caches | At most 100 cache names get a cache; a name beyond that runs uncached ([Limits](#limits)) |
| Size limits | A Redis key over 1 KiB is stored under its hash and still hits; a value over 1 MiB is not stored ([Size limits](#size-limits)) |
| JSON values | Redis values are plain JSON, read only as the `@Cacheable` method's own type and from allowed packages, never with Java serialization ([Stored values](cache-values.md)) |

The beans the starter adds, with their types:

| Bean | Type | Created when |
| --- | --- | --- |
| `cacheInterceptor`, `cacheOperationSource`, `org.springframework.cache.config.internalCacheAdvisor` | `CacheInterceptor`, `CacheOperationSource`, `BeanFactoryCacheOperationSourceAdvisor` (Spring's, through `@EnableCaching`) | No `@EnableCaching` is present yet |
| `stratumRedisCacheWriter` | `RedisCacheManagerBuilderCustomizer` | Spring Data Redis and a Redis connection are present; it sets a writer that clears with SCAN and applies the [size limits](#size-limits) |
| `stratumCacheLayerHook` | `StratumCacheLayerHook` | Always; it installs the layer on Spring's `cacheInterceptor` unless your own caching set-up says otherwise ([below](cache-redis-down.md)) |
| `cacheCounters` or `micrometerCacheCounters` | `CacheCounters` | Always; the Micrometer one when a `MeterRegistry` bean is present |
| `redisRetryGate` | `RedisRetryGate` | A Redis connection is present; it makes every Redis cache skip Redis for a while after failures in a row ([When Redis is down](cache-redis-down.md)) |
| `stratumRejectWhileDisconnected` | `LettuceClientOptionsBuilderCustomizer` | Lettuce is on the class path; it makes Lettuce refuse commands while disconnected |
| `stratumRedisAvailabilityMetric` | `MeterBinder` | Micrometer and a Redis connection are present; it registers the gauge `stratum.cache.redis.available` |
| `stratumCacheJsonValues`, `stratumRedisCacheJsonValues`, `typedCacheValues`, `stratumRedisCacheJacksonRequired` | See [Stored values](cache-values.md#when-json-values-apply) | Spring Boot builds its Redis cache manager and the service has no `CachingConfigurer` or `RedisCacheConfiguration` bean |
| `stratumCacheTimeToLive` | `RedisCacheManagerBuilderCustomizer` | A Redis connection is present; it sets each cache's own [time to live](cache-per-cache.md) |
| `stratumCacheCommandTimeouts` | `CacheCommandTimeouts` | Lettuce is on the class path; it sets each cache's own [command timeout](cache-per-cache.md#command-timeout) |
| `stratumCacheCommandTimeoutsCheck` | `SmartInitializingSingleton` | A Redis connection is present; it warns when caches set a command timeout but the client is not Lettuce |
| `stratumCaffeine`, `stratumCaffeineNullValues`, `stratumCaffeineCacheSettings` | See [Caffeine](cache-caffeine.md#beans) | Caffeine is on the class path |

Spring Boot's cache auto-configuration sets up a cache manager only once caching is switched on, so
Stratum's `@EnableCaching` is ordered before it. That is the one reason this module is ordered
before a Spring Boot auto-configuration.

## Properties

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.cache.enabled` | boolean | `true` | Whether the module is on. With `false`, Stratum does not switch caching on, adds no fail-open layer or cap, and sets none of the defaults below, for Redis or Caffeine, and Spring Boot's own apply. |
| `stratum.cache.max-caches` | integer, from 1 | `100` | The most cache names that get a cache. A name beyond it runs uncached ([Limits](#limits)). |
| `stratum.cache.warning-interval` | duration, from `1s` to `1h` | `1m` | How often one cache's warning, `SX-CACHE-1002` to `SX-CACHE-1005`, and the warning `SX-CACHE-1006` are repeated at most. |
| `stratum.cache.max-key-size` | data size, above 0 | `1KB` | The longest Redis key, prefix included, stored as it is. A longer key is stored under its SHA-256 hash ([Size limits](#size-limits)). |
| `stratum.cache.max-value-size` | data size, above 0 | `1MB` | The largest value a Redis cache stores. A larger one is not stored ([Size limits](#size-limits)). |
| `stratum.cache.redis-retry-interval` | duration, from `1s` to `5m` | `5s` | How long every Redis cache skips Redis after Redis could not be reached or a command timed out; then one call tries again ([When Redis is down](cache-redis-down.md)). |
| `stratum.cache.redis-failure-threshold` | integer, from 1 to 10 | `3` | How many Redis calls in a row that could not reach Redis or timed out make every Redis cache skip Redis. A call that reaches Redis starts the count again; `1` skips at the first failure ([When Redis is down](cache-redis-down.md)). |
| `stratum.cache.serialization.allowed-packages` | list of package names | none | Packages, beside the service's own and `java`, whose types Redis cache values may be read as ([Stored values](cache-values.md)). |
| `stratum.cache.caches.<name>.max-key-size` | data size, above 0 | `max-key-size` | The key limit of the cache `<name>`. |
| `stratum.cache.caches.<name>.max-value-size` | data size, above 0 | `max-value-size` | The value limit of the cache `<name>`, such as `5MB` for a cache of large reports. |
| `stratum.cache.caffeine.cache-null-values` | boolean | `false` | Whether Caffeine caches store a method's `null` result ([Null results](cache-null-results.md)). |
| `stratum.cache.caches.<name>.time-to-live` | duration, above 0 | `spring.cache.redis.time-to-live` for Redis, the builder's expiry (`10m`) for Caffeine | How long an entry of the cache `<name>` lives, in Redis or Caffeine ([per cache](cache-per-cache.md)). |
| `stratum.cache.caches.<name>.command-timeout` | duration, above 0, at most the larger of `10s` and `spring.data.redis.timeout` | `spring.data.redis.timeout` | How long a Redis command of the cache `<name>` waits, higher or lower than the global value. The largest one also bounds every connect ([per cache](cache-per-cache.md#command-timeout)). |
| `stratum.cache.caches.<name>.max-entries` | integer, above 0 | the builder's `maximumSize` (`10000`) | The most entries the Caffeine cache `<name>` holds ([Caffeine](cache-caffeine.md#settings-per-cache)). Redis caches ignore it. |

A value out of range stops startup with `SX-CORE-1003`, which names the key. A size of 0 or less, in any of the four size keys or `max-entries`, is reported as `stratum.cache.sizes-positive` instead, and a time to live or command timeout of 0 or less as `stratum.cache.durations-positive`.

Spring Boot properties this module gives a default (your values win):

| Spring Boot property | Type | Stratum default | Spring Boot's default | Meaning |
| --- | --- | --- | --- | --- |
| `spring.cache.redis.time-to-live` | duration | `10m` | unset: entries never expire | How long a Redis cache entry lives. |
| `spring.cache.redis.key-prefix` | string | `${spring.application.name}:` | unset: keys start with the cache name | The text every Redis cache key starts with, before `<cache>::`. See [Key prefix](#key-prefix). |
| `spring.cache.redis.enable-statistics` | boolean | `true` | `false` | Whether Redis caches count hits and misses for the metrics. |
| `spring.cache.redis.cache-null-values` | boolean | `false` | `true` | Whether Redis caches store a method's `null` result. See [Null results](cache-null-results.md). |
| `spring.cache.caffeine.spec` | string | none: Stratum's `stratumCaffeine` builder, `maximumSize=10000,expireAfterWrite=10m,recordStats` | unset: no bound, no expiry, no statistics | The builder of every Caffeine cache; yours replaces Stratum's whole builder ([Caffeine](cache-caffeine.md#your-own-caffeine-settings)). |
| `spring.data.redis.timeout` | duration | `1s` | unset: Lettuce's 60 seconds | How long any Redis command waits for its answer. |
| `spring.data.redis.connect-timeout` | duration | `2s` | unset: Lettuce's 10 seconds | How long connecting to Redis may take. |

These apply in both modes of `stratum.backward-compatible`, because no consumer of the service
sees them. The two `spring.data.redis.*` timeouts apply to every Redis call of the service, a
`RedisTemplate` included, not only to caches.

Set your own values in `application.properties`:

```properties
stratum.cache.max-caches=200
stratum.cache.warning-interval=5m
stratum.cache.redis-retry-interval=2s
stratum.cache.redis-failure-threshold=5
stratum.cache.max-key-size=2KB
stratum.cache.max-value-size=2MB
stratum.cache.caches.reports.max-value-size=5MB
stratum.cache.serialization.allowed-packages=com.acme.shared
spring.cache.redis.time-to-live=1h
spring.data.redis.timeout=2s
```

or in `application.yml`:

```yaml
stratum:
  cache:
    max-caches: 200
    warning-interval: 5m
    redis-retry-interval: 2s
    redis-failure-threshold: 5
    max-key-size: 2KB
    max-value-size: 2MB
    caches:
      reports:
        max-value-size: 5MB
    serialization:
      allowed-packages: com.acme.shared
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
`stratumRedisCacheWriter` customizer gives Spring Boot's cache manager a writer that finds them with
SCAN instead, asking Redis for 1,000 keys per call, so Redis keeps serving other commands between
calls. The writer takes no lock, as Spring Boot's does, and keeps the statistics setting.

Like Spring Boot's writer, it writes and clears in the background, as Spring Data Redis does with
Lettuce: right after a cached method or a clear returns, Redis may not hold the change yet, and an
entry may still be read for a moment. With `@CacheEvict(allEntries = true, beforeInvocation = true)`,
Spring clears the cache at once, before the method runs, also with SCAN.

## When Redis is down

A cached method keeps working while Redis is down or slow: a failed read is a miss, a failed write is
dropped, and Redis is skipped for a few seconds after it fails. See
[When Redis is down](cache-redis-down.md).

## Null results

A Redis or Caffeine cache does not store a method's `null` result, so the method runs again on the
next call; see [Null results](cache-null-results.md).

## Reactive and async methods

Methods that return a `Mono`, a `Flux` or a `CompletableFuture` are cached like other methods, with
Redis and Caffeine; see [Reactive and async methods](cache-reactive.md), which also says when
`sync = true` stops a stampede.

## Metrics

With Micrometer present, as with the actuator or the
[observability starter](../../starters/stratum-starter-observability/README.md), Spring Boot
reports `cache.gets` (with `result=hit` or `miss`), `cache.puts` and `cache.removals` for each
cache, tagged with `cache` and `cache.manager`. Spring Boot measures the caches the cache manager
knows at startup, and Stratum's layer has it measure each cache made later, on its first use, so
you need not name your caches. A cache reached only through a path that skips the layer
([When Redis is down](cache-redis-down.md)) is measured only if you name it:

```properties
spring.cache.cache-names=products,exchange-rates
```

```yaml
spring:
  cache:
    cache-names: products,exchange-rates
```

Stratum measures at most `stratum.cache.max-caches` caches this way; a name over the limit gets no
cache and so no metrics. Redis reports no evictions.

Stratum adds three counters of its own:

| Metric | Tags | Counts |
| --- | --- | --- |
| `stratum.cache.failures` | `cache`, `operation` (`get`, `put`, `evict`, `clear`) | Cache calls that failed and were handled fail-open ([When Redis is down](cache-redis-down.md)) |
| `stratum.cache.skips` | `cache` (`_over-limit`), `reason` (`cache-limit`) | Calls for a cache name over `stratum.cache.max-caches` ([Limits](#limits)) |
| `stratum.cache.skips` | `cache`, `reason` (`key-hashed`, `value-too-large`) | Calls whose key was hashed, and values not stored ([Size limits](#size-limits)) |
| `stratum.cache.skips` | `cache`, `reason` (`type-not-concrete`, `type-not-allowed`, `unreadable`) | Calls of a method whose type cannot be read, and stored values read as a miss ([Stored values](cache-values.md)) |
| `stratum.cache.redis.connect-failures` | none | Times Redis could not be reached and the caches started skipping it ([When Redis is down](cache-redis-down.md)) |

and one gauge: `stratum.cache.redis.available`, 1 while Redis caches use Redis and 0 while they skip it
until Redis answers again. It reads 1 before the first cache call. Alert when it stays at 0.
Redis command spans carry the name of their cache ([Traces](cache-traces.md)).

## An existing service

Adding the starter to a service that already caches in Redis changes this:

- The 1 s and 2 s timeouts reach every Redis call of the service, `RedisTemplate` included.
- Entries expire after 10 minutes by default, where they may have lived for ever.
- The new key prefix leaves the old keys unread. Delete them with SCAN by pattern, never with
  `FLUSHDB` on a Redis shared with other data; see
  [Migrating a service, step 7](../guides/migrating-a-service.md#step-7-data-cache-kafka).
- `SX-CACHE-1001` stops startup when the service has no name ([Key prefix](#key-prefix)).
- Values are stored as JSON and read only as allowed types; Jackson must be on the class path
  ([Stored values](cache-values.md)).
- During a Redis outage a cached method now runs and returns its result, where the call used to
  fail, and Redis caches skip Redis for a few seconds.
- Lettuce refuses every Redis command of the service while its connection is down, where it used
  to hold the command until it reconnected.

The timeouts, expiry and key prefix can be set back with the Spring Boot property in the
[table above](#properties). The last two come with the module: `stratum.cache.redis-retry-interval`
sets how long Redis is skipped, `stratum.cache.redis-failure-threshold` after how many failures, and your own `LettuceClientOptionsBuilderCustomizer` changes
Lettuce's refusal ([When Redis is down](cache-redis-down.md)).

## Configure in Java

Every `stratum.cache.*` setting can also be set in Java, through a
`StratumCachePropertiesCustomizer` class, and Java wins over the files:

```java
public class OrdersCacheSettings implements StratumCachePropertiesCustomizer {

  @Override
  public StratumCacheProperties customize(StratumCacheProperties properties) {
    return properties.toBuilder()
        .maxCaches(200)
        .warningInterval(Duration.ofMinutes(5))
        .redisRetryInterval(Duration.ofSeconds(2))
        .redisFailureThreshold(5)
        .maxKeySize(DataSize.ofKilobytes(2))
        .maxValueSize(DataSize.ofMegabytes(2))
        .caches(
            Map.of(
                "reports",
                StratumCacheProperties.CacheSettings.builder()
                    .maxValueSize(DataSize.ofMegabytes(5))
                    .build()))
        .serialization(new StratumCacheProperties.Serialization(List.of("com.acme.shared")))
        .build();
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
  order, or name it `stratumRedisCacheWriter` to replace Stratum's. A writer of your own replaces
  Stratum's, and with it the SCAN clears and the [size limits](#size-limits).
- A bean named `stratumRedisCacheWriter` replaces Stratum's customizer. Note: it backs off by its name,
  not by its type, as most beans do ([Overriding defaults, level 3](../configuration/overriding-defaults.md#level-3-replace-a-bean)),
  because a customizer of yours of the same type must not switch SCAN off.
- A bean named `stratumRejectWhileDisconnected` replaces Stratum's Lettuce customizer, and your
  own `LettuceClientOptionsBuilderCustomizer` beans run after it, so they win.
- A bean named `stratumRedisAvailabilityMetric` replaces Stratum's gauge. It backs off by its name,
  because your own `MeterBinder` beans measure other things.
- `redisRetryGate` backs off to any `RedisRetryGate` bean, but that type is internal to Stratum. To
  change how long Redis is skipped, set `stratum.cache.redis-retry-interval` instead, and to change
  after how many failures in a row, `stratum.cache.redis-failure-threshold`.
- Your own Spring Data `RedisCacheConfiguration` bean replaces Spring Boot's cache settings, and so
  Stratum's time to live, key prefix, [null setting](cache-null-results.md) and JSON values: Spring Data's
  `defaultCacheConfig()` stores nulls. The SCAN writer and the size limits still apply. A
  `CachingConfigurer` bean also keeps Spring Boot's values ([Stored values](cache-values.md)).
- Your own `CacheManager` bean replaces Spring Boot's, and with it Stratum's time to live, key
  prefix, statistics, null setting, SCAN clears and size limits. Stratum's `@EnableCaching`, the
  Redis timeouts and the [name check](#key-prefix) still apply.

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
defaults: time to live, key prefix, statistics, timeouts, clearing with SCAN, the size limits, the
fail-open layer, the cap on caches, skipping Redis while it fails, Lettuce's refusal while
disconnected, and the time to live and command timeout of single caches. A service with its
own `@EnableCaching` keeps caching, with Spring Boot's defaults; without one, `@Cacheable` methods
then always run.

## Limits

- Other cache types than Redis and Caffeine, such as Spring Boot's simple in-memory cache, get
  Spring Boot's behaviour only. Caffeine's own limits are in [Caffeine](cache-caffeine.md#not-covered).
- Redis memory is bounded by the server's `maxmemory` policy, which your team sets.
- Redis reports no evictions to the metrics.
- At most `stratum.cache.max-caches` cache names, 100 by default, get a cache. Calls for a name
  beyond that run uncached, through one shared no-op cache, and no cache is created for the name.
  Each such call is counted in `stratum.cache.skips` under `cache=_over-limit`, and
  [`SX-CACHE-1003`](../guides/error-codes.md#stratum-starter-cache) is logged at most once per
  `stratum.cache.warning-interval`.

### Size limits

Stratum's Redis writer bounds the bytes of each entry, for every cache, however it is called:

- **Keys.** A key longer than `stratum.cache.max-key-size`, 1 KiB by default, prefix included, is
  stored as `<prefix><cache>::sha256:<64 hex digits>`, such as
  `order-service:products::sha256:9f86d0…`. Reads, writes and evicts use the same hashed key, so the
  entry still hits and `@CacheEvict(allEntries = true)` still clears it. A 10 MB key takes about 100
  bytes in Redis. The key's own text, which may hold personal data, is never stored or logged. A
  short key that itself starts with `sha256:` is hashed too, so it can never read another key's
  entry.
- **Values.** A value larger than `stratum.cache.max-value-size`, 1 MiB by default, is not stored:
  the method's result is returned as usual and the next call runs the method again. When an
  `@CachePut` replaces an entry with a value too large, the older entry is removed instead, so no
  stale value is read; like other writes, Redis may remove it a moment later.
- Each case is counted in `stratum.cache.skips` under the cache's name, with `reason=key-hashed`
  or `reason=value-too-large` ([Metrics](#metrics)). Nothing is logged.
- Each cache can have its own limits, under `stratum.cache.caches.<name>.*`
  ([Properties](#properties)).

Not covered: a cache manager or Redis cache writer of your own ([Customize](#customize)), and a
key prefix of your own (a `CacheKeyPrefix`) that leaves out `<cache>::` or is longer than
`max-key-size`. With such a prefix, a hashed key is stored as `sha256:<hex>` alone, which `allEntries` does not clear; it expires with its time to
live. Caches other than Redis have no byte limits.

### Not planned

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
| `cache.gets` is missing for a cache | The cache is reached by a path that skips Stratum's layer, or its name is over `stratum.cache.max-caches`. List it in `spring.cache.cache-names` ([Metrics](#metrics)). |
| Redis calls time out after 1 second | A command takes longer than Stratum's `spring.data.redis.timeout`. Raise it for the whole service, or for one cache with its own `command-timeout` ([per cache](cache-per-cache.md)). |
| `SX-CACHE-1002` in the log | A cache call failed, usually because Redis is down; the method still ran. Check Redis ([When Redis is down](cache-redis-down.md)). |
| The method always runs for one key, and `stratum.cache.skips` counts `value-too-large` | Its result is larger than `stratum.cache.max-value-size`. Return less, or raise the limit for that cache ([Size limits](#size-limits)). |
| `SX-CACHE-1006` in the log, or `stratum.cache.redis.available` at 0 | Redis could not be reached or answered too slowly several times in a row, so Redis caches skip it for a few seconds at a time. Check Redis and the network ([When Redis is down](cache-redis-down.md)). |
| Redis commands fail at once during a short reconnect | Stratum makes Lettuce refuse commands while disconnected. Declare your own `LettuceClientOptionsBuilderCustomizer` to change it ([When Redis is down](cache-redis-down.md)). |
| `SX-CACHE-1004` in the log, and the method always runs | Its return type is `Object`, an interface, an abstract class, or from a package not allowed. Return a concrete type or allow its package ([Stored values](cache-values.md)). |
| `SX-CACHE-1005` in the log | A stored value no longer fits its class, often after a release. It was a miss and is replaced; to avoid a burst, rename the cache ([Stored values](cache-values.md#moving-from-java-serialized-values)). |
| Startup stops with `SX-CACHE-1007` | Jackson is missing. Add `spring-boot-starter-json` ([Stored values](cache-values.md#when-json-values-apply)). |
| Startup stops with `SX-CACHE-1008` | A Caffeine cache sets `max-entries` or `time-to-live`, but your own Caffeine builder has no `maximumSize` or `expireAfterWrite`, or `spring.cache.cache-names` leaves the cache out ([Caffeine](cache-caffeine.md#settings-per-cache)). |
| `SX-CACHE-1003` in the log | The service uses more than `stratum.cache.max-caches` cache names. Name caches explicitly, so they stay few, or raise the limit ([Limits](#limits)). |
