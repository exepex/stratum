# Cache: the cache name on Redis spans

**Status: Built**, in `stratum-starter-cache`, unless `stratum.cache.enabled=false`.

[← Cache](cache.md) · [Documentation home](../README.md)

## What it does

When your service traces, as it does with the
[observability starter](../../starters/stratum-starter-observability/README.md), the Lettuce client
records one span for each Redis command, such as `get` or `set`. Without help, a span does not say
which cache the command was for. Stratum adds one attribute to the span of each command whose key
belongs to one of your caches:

| Attribute | Example | Where |
| --- | --- | --- |
| `cache.name` | `products` | Spans of Redis commands for a cache, never metrics |

The key itself, such as a product ID, never goes on the span. The attribute is a *high-cardinality*
value: Micrometer puts it on spans only, never as a metric tag, so Spring Boot's `lettuce` timer
keeps the same series. There is no property to set; it applies in both modes of
`stratum.backward-compatible`, because no consumer of your service sees a span.

## Which caches are named

- every cache your `@Cacheable`, `@CachePut` or `@CacheEvict` methods use, from its first use, up to
  `stratum.cache.max-caches` ([Limits](cache.md#limits));
- every cache listed in `spring.cache.cache-names`, even when your code reaches it through the
  `CacheManager` itself.

Other Redis keys, such as your own `RedisTemplate` keys, carry no cache name, even when they look
like a cache key. So does a command without a key, such as `PING` or the `SCAN` of a clear.

To name a cache that only your own code reaches through the `CacheManager`, list it, in
`application.properties`:

```properties
spring.cache.cache-names=products,exchange-rates
```

or in `application.yml`:

```yaml
spring:
  cache:
    cache-names: products,exchange-rates
```

## When it does not apply

- **Lettuce only.** Spring Boot records Redis spans for the Lettuce client, its default, and not for
  Jedis.
- **No observation registry.** Spring Boot makes one when Micrometer is present, as with the
  actuator. Without it, there are no Redis spans and Stratum adds nothing.
- **`spring.cache.redis.use-key-prefix=false`.** Keys then carry no cache name, so no span is named.

## Cost

Whenever Spring Boot has an observation registry, which it has with metrics even when tracing is
off, Stratum reads the start of each Redis command's key: at most the key prefix, the longest cache
name and two bytes. A long key costs no more than a short one.

## Customize

Declare a bean named `stratumCacheNameOnSpans` of type `ObservationFilter` to replace Stratum's
filter, for example with one that does nothing.
