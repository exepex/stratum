# Overriding defaults

**Status: Planned.** Stratum's defaults suit most services, but never all of them. This page shows the
four ways to change what Stratum does, from the lightest touch to the strongest.

[← Documentation home](../README.md)

| Level | You want to | You do | Effort |
| --- | --- | --- | --- |
| 1 | Change a value | Set a property | One line |
| 2 | Adjust a bean Stratum built | Declare a customizer bean | A few lines |
| 3 | Replace a bean entirely | Declare your own bean of the same type | You own that bean from now on |
| 4 | Turn a module off | `stratum.<module>.enabled=false` | One line |

Always use the **lowest level that solves your problem**. Each level up means more code that your
team maintains instead of Stratum.

## Level 1: change a value

Every default is a property. Find it on the module page or in the
[Configuration reference](reference.md), then set it:

```yaml
stratum:
  http-client:
    defaults:
      read-timeout: 10s
  logging:
    redaction:
      extra-keys:
        - iban
        - date-of-birth
```

```properties
stratum.http-client.defaults.read-timeout=10s
stratum.logging.redaction.extra-keys=iban,date-of-birth
```

## Level 2: adjust a bean with a customizer

A **customizer** is a small bean that receives a builder or a bean Stratum created and changes part
of it. Stratum keeps building the bean; your customizer runs on top. You keep every other default.

Add a header to every outgoing HTTP call:

```java
@Configuration
class HttpClientConfig {

  @Bean
  RestClientCustomizer clientNameHeader() {
    return builder -> builder.defaultHeader("X-Client-Name", "order-service");
  }
}
```

Customizers Stratum supports (all planned):

| Customizer | Adjusts | Module |
| --- | --- | --- |
| `RestClientCustomizer` (Spring Boot) | Every HTTP client | http-client |
| `StratumHttpClientCustomizer` | One named client group | http-client |
| `StratumSecurityCustomizer` | The `HttpSecurity` before Stratum's chain is built | security |
| `StratumErrorResponseCustomizer` | Extra fields on every error response | web |
| `MeterFilter` / `ObservationFilter` (Micrometer) | Metric names, tags and observations | observability |
| `StratumCacheCustomizer` | Each cache before it is created | cache |
| `StratumKafkaErrorHandlerCustomizer` | Retry and dead-letter handling | kafka |

When several customizers of the same type exist, Spring applies them in `@Order` order.

## Level 3: replace a bean

Every bean Stratum creates is marked `@ConditionalOnMissingBean`. If your application defines a bean
of the same type, Stratum's version is not created and yours is used.

Example: a service with very specific security rules defines its own filter chain:

```java
@Configuration
class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/internal/**").hasRole("OPS")
            .anyRequest().authenticated())
        .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
        .build();
  }
}
```

> [!WARNING]
> When you replace a bean, **you** now own everything it did. In the example above you lose
> Stratum's public paths, CORS rules and error responses for `401` and `403`. Prefer a customizer
> (level 2) when you only need to add something.

Each module page lists the beans it creates under **What you get**, with their types, so you know
exactly what you would be replacing.

## Level 4: turn a module off

Every starter has a switch. Turning it off removes all of its beans and defaults, as if the starter
were not there, while keeping its libraries on the classpath:

```yaml
stratum:
  cache:
    enabled: false
```

```properties
stratum.cache.enabled=false
```

Useful for a test profile, or while you migrate one concern at a time. If you never want the module,
remove the starter from your `pom.xml` instead.

Some modules also let you switch off one part:

| Property | Turns off |
| --- | --- |
| `stratum.web.request-logging.enabled=false` | Only the per-request log line |
| `stratum.logging.redaction.enabled=false` | Only redaction (not recommended) |
| `stratum.observability.tracing.enabled=false` | Only tracing; metrics and health stay |

## Advanced: exclude one auto-configuration class

If you must, you can exclude a single Stratum auto-configuration class, as with any Spring Boot
auto-configuration:

```yaml
spring:
  autoconfigure:
    exclude:
      - com.example.stratum.web.autoconfigure.StratumCorrelationAutoConfiguration
```

```properties
spring.autoconfigure.exclude=com.example.stratum.web.autoconfigure.StratumCorrelationAutoConfiguration
```

Class names are internal and may change between minor versions. Prefer the `enabled` switches,
which are part of Stratum's public contract.

## Tell us when you override

If you find yourself overriding the same default in several services, the default is probably
wrong. Open an issue so it can be fixed once in Stratum, which is the whole point of having it.
