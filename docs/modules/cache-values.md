# Cache: stored values

**Status: Built**, in `stratum-starter-cache`, unless `stratum.cache.enabled=false`.

[← Cache](cache.md) · [Documentation home](../README.md) · Property prefix: `stratum.cache.serialization`

## What it does

Spring Boot stores Redis cache values with Java serialization by default. Reading those bytes back
can build any class on the class path, so anyone who can write to Redis can run code in the
service. Stratum stores each value as plain JSON instead, with no class name in the bytes, and
reads it back only as the return type of the `@Cacheable` method that asks for it:

```java
@Cacheable("orders")
public Order orderOf(String id) { ... }
```

stores `{"id":"order-1","quantity":3}` under `order-service:orders::order-1`, and a hit returns an
`Order`. A loaded value on a miss is returned as the method's own object, not read back from JSON.

- **Declared types.** `Optional<T>`, `CompletableFuture<T>` and `Mono<T>` are stored as `T`, and
  `Flux<T>` as `List<T>`, as Spring stores them. Type arguments and arrays are read as declared,
  so a `List<Instant>` comes back as instants, not strings.
- **Class changes.** A field added since the value was stored gets its default (`0`, `false` or
  `null`), and a field that is no longer in the class is ignored. A field whose type changed makes
  the value unreadable.
- **Unreadable is a miss.** A value that cannot be read as the type, or is larger than the cache's
  `max-value-size`, is a miss: the method runs and its result replaces the entry. It is counted in
  `stratum.cache.skips` with `reason=unreadable` and warned as
  [`SX-CACHE-1005`](../guides/error-codes.md#stratum-starter-cache), once per cache per
  `stratum.cache.warning-interval`. With `sync = true`, the old entry is deleted at once and the
  method loads it again; if that read fails too, the method runs without the cache.
- **Stored nulls.** With `spring.cache.redis.cache-null-values=true`, a stored null is a hit, as
  in Spring Boot; Redis keeps it in its own marker, not as JSON.
- One INFO line at startup names the allowed packages.

## Which types are allowed

A `@Cacheable` method's return type, each of its type arguments and each array component must be:

- **concrete**: not `Object`, not an interface and not an abstract class. Collections and maps
  are the exception: `List<Order>`, `Set<UUID>` and `Map<String, Order>` are allowed;
- **from an allowed package**: the service's own package (the package of its
  `@SpringBootApplication` class and below), a `java` package, or a package listed in
  `stratum.cache.serialization.allowed-packages`. A primitive, such as `int`, is allowed.

A method whose type breaks either rule runs without the cache: nothing is read from or written to
Redis for it. Each call is counted in `stratum.cache.skips` with `reason=type-not-concrete` or
`reason=type-not-allowed`, and [`SX-CACHE-1004`](../guides/error-codes.md#stratum-starter-cache) is
warned once per cache per `stratum.cache.warning-interval`. The warning names the cache, the type
and the setting, never a key or a value.

A package matches only as a whole name: allowing `com.acme.orders` allows `com.acme.orders.model`
but never `com.acme.ordersevil`.

A class named inside the JSON through `@JsonTypeInfo` is checked by name before it is loaded, and
allowed only from the service's own packages and `allowed-packages`, never from `java`. A refused
name makes the value unreadable, and the class is never loaded or built.

Allow the packages of value types that come from a shared library:

```properties
stratum.cache.serialization.allowed-packages=com.acme.shared,com.acme.billing
```

```yaml
stratum:
  cache:
    serialization:
      allowed-packages: com.acme.shared,com.acme.billing
```

or in Java, in your `StratumCachePropertiesCustomizer` ([Configure in Java](cache.md#configure-in-java)):

```java
return properties.toBuilder()
    .serialization(new StratumCacheProperties.Serialization(List.of("com.acme.shared")))
    .build();
```

A blank entry stops startup with `SX-CORE-1003`.

## When JSON values apply

Stratum's JSON values are added when all of these hold:

- the module is on, Spring Data Redis and a Redis connection are present, and Spring's caching
  interceptor is there (Stratum's `@EnableCaching` or the service's own);
- the service declares no `CachingConfigurer` bean and no Spring Data `RedisCacheConfiguration`
  bean;
- Spring Boot builds its Redis cache manager: no `CacheManager` bean of the service, and
  `spring.cache.type` unset or `redis`.

Then every Redis cache, those listed in `spring.cache.cache-names` and those made on first use,
stores JSON. Otherwise the values keep the serializer Spring Boot or your own configuration sets.

Jackson (`tools.jackson.core:jackson-databind`) must be on the class path. The cache starter does
not bring it; Spring Boot's web and JSON starters do. When the conditions above hold and Jackson is
missing, startup stops with [`SX-CACHE-1007`](../guides/error-codes.md#stratum-starter-cache). Add
`org.springframework.boot:spring-boot-starter-json`, declare your own `RedisCacheConfiguration`
bean, or set `stratum.cache.enabled=false`.

A `RedisCacheManagerBuilderCustomizer` of yours runs after Stratum's and wins, so it can set its own
value serializer. A cache that does not store Stratum's JSON is read as Spring Data reads it.

Beans:

| Bean | Type | Created when |
| --- | --- | --- |
| `stratumCacheJsonValues` | `CacheJsonValues` | The conditions above hold and Jackson is present |
| `stratumRedisCacheJsonValues` | `RedisCacheManagerBuilderCustomizer` | The same; it sets JSON values on every Redis cache |
| `typedCacheValues` | `TypedCacheValues` | The same; Stratum's cache layer reads each value as its method's type |
| `stratumRedisCacheJacksonRequired` | `RedisCacheManagerBuilderCustomizer` | The conditions above hold and Jackson is missing; it stops startup with `SX-CACHE-1007` |

## What is not covered

- **Only through the annotations.** Types are read only through Spring's caching annotations with
  Stratum's layer. A direct `cacheManager.getCache("orders").get(key)` returns Stratum's internal
  holder of the raw JSON, not an `Order`.
- **A named cache manager or resolver.** `@Cacheable(cacheManager = "cacheManager")`, a
  `cacheResolver`, or the same in `@CacheConfig`, skips Stratum's layer. The first call stores
  JSON; the next one fails in the caller with a `ClassCastException`, which no Stratum metric
  counts. Leave the cache manager unnamed, or declare your own `RedisCacheConfiguration`.
- **One cache name per value type.** Two methods that share a cache name and a key, with different
  return types, read each other's JSON as their own type, unknown fields ignored. Give each type
  its own cache name.
- **Interfaces with named subtypes.** An interface with `@JsonTypeInfo(use = NAME)` and
  `@JsonSubTypes` would read safely, but it is refused as not concrete, and counted.
- **No trace ID on background warnings.** For a method that returns a `CompletableFuture` or a
  `Mono`, the value is read on Redis's client thread, so its `SX-CACHE-1005` line has no trace ID.

## Moving from Java-serialized values

Entries that an older version stored with Java serialization cannot be read as JSON: each is read
once as a miss, warned as `SX-CACHE-1005`, and replaced. To avoid that burst, give the cache a new
name, such as `orders-v2`, or delete the old keys once the new version runs, with SCAN by pattern
and never with `FLUSHDB` on a Redis shared with other data. For the cache `orders`:

```bash
redis-cli --scan --pattern 'order-service:orders::*' | xargs -r redis-cli unlink
```

The same applies when a class changes so much that most of its entries become unreadable.
