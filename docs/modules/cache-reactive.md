# Cache: reactive and async methods

**Status: Built**, in `stratum-starter-cache`, unless `stratum.cache.enabled=false`.

[← Cache](cache.md) · [Documentation home](../README.md)

## What it does

A `@Cacheable` method that returns a `Mono`, a `Flux` or a `CompletableFuture` is cached like a
method that returns its value directly, with Redis and with Caffeine, with or without `sync = true`.
A `Mono` is a Reactor value that arrives later; a `Flux` is a stream of such values. You write
nothing extra:

```java
@Cacheable(cacheNames = "products", sync = true)
public Mono<Product> productOf(String sku) {
  return webClient.get().uri("/products/{sku}", sku).retrieve().bodyToMono(Product.class);
}
```

Such a method gets the same as a blocking one:

- **Fail-open.** When Redis fails or is skipped, the method runs and its value is returned
  ([When Redis is down](cache-redis-down.md)).
- **The method runs once.** With `sync = true`, when the write after a load fails, the loaded value
  is returned, the method is not run a second time, and one `put` failure is counted.
- **Null results.** A `Mono` that ends with no value, or a future of `null`, is not stored and is
  not a failure; the method runs again on the next call ([Null results](cache-null-results.md)).
- **Stored values.** On Redis, the value inside is stored as JSON and read back as the declared
  type ([Stored values](cache-values.md)). A `Flux` is stored as one list of its values.

This does not need WebFlux: a service on Spring MVC with `CompletableFuture` methods gets the same.

## Caffeine

Spring Boot builds Caffeine caches without Caffeine's async mode, and Spring's own Caffeine cache
cannot answer these methods in that mode. Stratum answers them itself, from the same Caffeine
cache: a hit is answered at once, and a loaded value is stored when the method's value arrives.

Stratum does not turn on Caffeine's async mode, because it would run every blocking `sync = true`
method on Java's shared `ForkJoinPool` instead of the caller's thread, losing the caller's logging
and tracing context and its transaction. If you turn async mode on yourself, with a
`CacheManagerCustomizer<CaffeineCacheManager>` that calls `setAsyncCacheMode(true)`, Caffeine's own
answers are used instead.

## Stampedes

A stampede is many calls that miss the same key at the same moment, each running the method. Only
`sync = true` helps, and only in some cases:

| Cache | Blocking method with `sync = true` | `Mono`, `Flux` or `CompletableFuture` |
| --- | --- | --- |
| Caffeine | Runs once per key in each instance; the other callers wait for its value | Each concurrent miss runs the method |
| Redis | Each concurrent miss runs the method | Each concurrent miss runs the method |

No lock is taken on Redis, so several instances, or several calls in one instance, can load the
same key at once. The last value written wins. For an expensive method, keep its time to live long
enough that misses stay rare ([per cache](cache-per-cache.md)).

## Limits

- **A `Flux` is held whole in memory.** Spring collects every value of a `Flux` into one list
  before it is stored, so a very long `Flux` takes memory for all its values at once. On Redis, a
  list larger than `stratum.cache.max-value-size` is not stored ([Size limits](cache.md#size-limits)).
- **No trace ID on some warnings.** The write after a load runs on the thread that delivers the
  method's value, so an `SX-CACHE-1002` warning for that write has no trace ID.
- **Your own error handler.** When your own `CacheErrorHandler` rethrows write errors, a failed
  write after a load reaches it twice, first as a `put` and then as a `get`; if it swallows `get`
  errors, the method may also run a second time.
