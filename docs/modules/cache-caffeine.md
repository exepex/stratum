# Cache: Caffeine

**Status: Built**, in `stratum-starter-cache`, unless `stratum.cache.enabled=false`.

[← Cache](cache.md) · [Documentation home](../README.md) · Property prefix: `stratum.cache`

## What it does

Caffeine keeps each cache in the service's own memory. Spring Boot's Caffeine caches have no limit
by default: a busy cache grows until the service runs out of memory, and an entry never expires, so
a value changed by another pod is served stale for good. Stratum gives them safe defaults:

- **Bounded size.** Each cache holds at most 10,000 entries. When it is full, Caffeine drops the
  entries least likely to be read again.
- **Entries expire.** Each entry lives 10 minutes, as a Redis entry does with Stratum.
- **Hits and misses recorded**, so `cache.gets` reports them ([Metrics](cache.md#metrics)).
- **Null results not stored**, as with Redis ([Null results](cache-null-results.md)).
- **Settings per cache.** One cache can have its own bound and time to live.

These apply in both modes of `stratum.backward-compatible`, because no consumer of the service sees
them.

Caffeine's async mode stays off; methods that return a `Mono`, a `Flux` or a `CompletableFuture`
are still cached ([Reactive and async methods](cache-reactive.md#caffeine)).

## Use it

Add Caffeine next to the cache starter:

```xml
<dependency>
  <groupId>com.github.ben-manes.caffeine</groupId>
  <artifactId>caffeine</artifactId>
</dependency>
```

Spring Boot then picks Caffeine, unless Redis is also present: Spring Boot picks Redis first. With
both, set `spring.cache.type=caffeine` to use Caffeine. Byte limits (`max-key-size`,
`max-value-size`) apply to Redis only, and `max-entries` applies to Caffeine only.

## Settings per cache

In `application.properties`:

```properties
stratum.cache.caches.reports.max-entries=500
stratum.cache.caches.prices.time-to-live=30s
```

or in `application.yml`:

```yaml
stratum:
  cache:
    caches:
      reports:
        max-entries: 500
      prices:
        time-to-live: 30s
```

- **`max-entries`**: the most entries that cache holds, such as 500 for a cache of large reports.
  Above 0.
- **`time-to-live`**: how long an entry of that cache lives, the same key as for
  [Redis](cache-per-cache.md).

Both keys are in [Cache → Properties](cache.md#properties). Each such cache is created at startup.
Startup stops with [`SX-CACHE-1008`](../guides/error-codes.md#stratum-starter-cache), naming the
cache, when the setting cannot apply: your own builder has no `maximumSize` (for `max-entries`) or
no `expireAfterWrite` (for `time-to-live`), or `spring.cache.cache-names` lists the caches and
leaves this one out.

## Your own Caffeine settings

Spring Boot's own routes win, in this order, and each replaces Stratum's whole builder:
`spring.cache.caffeine.spec`, a `CaffeineSpec` bean, a `Caffeine` bean. Keep `recordStats` in your
own builder, or `cache.gets` reads 0. Leave out `expireAfterWrite` to keep entries until the cache
is full. In `application.properties`:

```properties
spring.cache.caffeine.spec=maximumSize=200000,expireAfterWrite=1h,recordStats
```

or in `application.yml`:

```yaml
spring:
  cache:
    caffeine:
      spec: maximumSize=200000,expireAfterWrite=1h,recordStats
```

To store `null` results, in `application.properties`:

```properties
stratum.cache.caffeine.cache-null-values=true
```

or in `application.yml`:

```yaml
stratum:
  cache:
    caffeine:
      cache-null-values: true
```

In Java, set the `stratum.cache.*` keys with a `StratumCachePropertiesCustomizer`
([Configure in Java](cache.md#configure-in-java)), and the builder with your own `Caffeine` bean.

## Beans

| Bean | Type | Created when |
| --- | --- | --- |
| `stratumCaffeine` | `Caffeine<Object, Object>` | Caffeine is on the class path and the service has no `Caffeine` bean; Spring Boot uses it only without a spec or `CaffeineSpec` bean |
| `stratumCaffeineNullValues` | `CacheManagerCustomizer<CaffeineCacheManager>` | Caffeine is on the class path; it runs before your own customizers, so theirs win |
| `stratumCaffeineCacheSettings` | `CaffeineCacheSettingsCustomizer` | Caffeine is on the class path; it runs after your own customizers |
| `stratumCaffeineFutures` | `CaffeineFutures` | Caffeine is on the class path; it lets Caffeine caches answer reactive and async methods |

Replace any of them with a bean of the same name, except `stratumCaffeineFutures`: its type is
internal to Stratum. To have Caffeine answer those methods itself, turn on its async mode
([Reactive and async methods](cache-reactive.md#caffeine)).

## Not covered

Byte limits: a Caffeine value's size in memory is not measured. An evict reaches only the pod that
runs it; other pods keep their entry until it expires.
