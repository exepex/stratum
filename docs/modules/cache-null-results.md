# Cache: null results

**Status: Built**, in `stratum-starter-cache`, unless `stratum.cache.enabled=false`.

[← Cache](cache.md) · [Documentation home](../README.md)

## What it does

A Redis or Caffeine cache does not store a method's `null` result: the method runs again on the
next call for that key, and you write no `unless = "#result == null"`. A `null` result is not a
failure: it is not counted in `stratum.cache.failures` and logs nothing.

- **A null removes the old entry.** A `null` written to a cache, such as by a `@CachePut` method
  that returns `null`, deletes the key's entry, so the next call runs the method. With Redis, each delete is one
  Redis command, sent in the background with Lettuce, Spring Boot's default Redis client, and is
  counted in `cache.removals`. That includes every
  `@Cacheable` miss whose method returns `null`.
- **Reactive and async methods.** A `Mono` that ends with no value, or a `CompletableFuture` of
  `null`, is treated the same, with or without `sync = true`
  ([Reactive and async methods](cache-reactive.md)).

To store `null` results again in Redis, set Spring Boot's key in `application.properties`:

```properties
spring.cache.redis.cache-null-values=true
```

or in `application.yml`:

```yaml
spring:
  cache:
    redis:
      cache-null-values: true
```

For Caffeine, set `stratum.cache.caffeine.cache-null-values=true`
([Caffeine](cache-caffeine.md#your-own-caffeine-settings)).
