# Cache: `stratum-starter-cache`

**Status: Planned.** Caching with Spring's `@Cacheable`, backed by Caffeine (in memory) or Redis
(shared), with a size limit and expiry on every cache.

[← Documentation home](../README.md) · Property prefix: `stratum.cache`

## What it does

A cache without a size limit or expiry is a memory leak that waits for traffic. This starter makes
every cache bounded and expiring by default, prefixes keys with the service name so services never
read each other's entries, and publishes hit and miss metrics.

| Type | Where data lives | Use when |
| --- | --- | --- |
| `caffeine` (default) | Memory of each instance | Data may differ briefly between instances; fastest |
| `redis` | Shared Redis server | All instances must see the same entries, or the data is large |

## Add it

```xml
<dependency>
  <groupId>com.example.stratum</groupId>
  <artifactId>stratum-starter-cache</artifactId>
</dependency>
```

For Redis, also set `stratum.cache.type=redis` and Spring Boot's `spring.data.redis.*` connection
properties. The Redis client library is already included.

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
| Caching switched on | No `@EnableCaching` needed |
| Bounded caches | Every cache has a maximum size (Caffeine) and a time to live |
| Key prefix | Redis keys start with `<spring.application.name>:` |
| Values as JSON | Redis values are stored as JSON, readable and safe across versions |
| Metrics | `cache.gets` (hit and miss), `cache.puts`, `cache.evictions` per cache |
| Failure tolerance | If Redis is down, calls go to the real method and an error is logged; requests do not fail |

## Properties

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.cache.enabled` | boolean | `true` | Switches the whole starter on or off. |
| `stratum.cache.type` | `caffeine`, `redis` | `caffeine` | Cache backend. |
| `stratum.cache.default-ttl` | duration | `10m` | Time to live for caches without their own setting. |
| `stratum.cache.default-max-size` | int | `10000` | Maximum entries per cache (Caffeine only). |
| `stratum.cache.key-prefix` | string | `<spring.application.name>:` | Prefix for Redis keys. |
| `stratum.cache.allow-unconfigured` | boolean | `true` | Allows caches not listed under `caches`, using the defaults. Set `false` to catch typos in cache names. |
| `stratum.cache.caches.<name>.ttl` | duration | `default-ttl` | Time to live for one cache. |
| `stratum.cache.caches.<name>.max-size` | int | `default-max-size` | Maximum entries for one cache. |

## Examples

```yaml
stratum:
  cache:
    type: caffeine
    default-ttl: 5m
    caches:
      products:
        ttl: 1h
        max-size: 50000
      exchange-rates:
        ttl: 30s
```

```properties
stratum.cache.type=caffeine
stratum.cache.default-ttl=5m
stratum.cache.caches.products.ttl=1h
stratum.cache.caches.products.max-size=50000
stratum.cache.caches.exchange-rates.ttl=30s
```

Redis:

```yaml
spring:
  data:
    redis:
      host: redis.internal
      port: 6379
      password: ${REDIS_PASSWORD}
stratum:
  cache:
    type: redis
```

```properties
spring.data.redis.host=redis.internal
spring.data.redis.port=6379
spring.data.redis.password=${REDIS_PASSWORD}
stratum.cache.type=redis
```

## Customize

Declare a `StratumCacheCustomizer` bean to adjust each cache as it is created, or your own
`CacheManager` bean to replace the setup.

## Switch it off

`stratum.cache.enabled=false` turns caching off; `@Cacheable` methods then always run.

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| The method always runs | Called from the same class (proxy bypassed), or the method is not `public`. |
| Stale data after an update | Add `@CacheEvict` or `@CachePut` to the method that changes the data, or lower the TTL. |
| Hit ratio near zero | The key includes a value that changes on every call (for example a timestamp). Set `key` explicitly. |
