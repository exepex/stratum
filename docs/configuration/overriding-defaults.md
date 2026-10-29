# Overriding defaults

**Status: Planned.** Only the Java configuration of [level 1](#set-the-same-values-in-java) is
built, in `stratum-core`. Stratum's defaults suit most services, but never all of them. This page
shows the four ways to change what Stratum does, from the lightest touch to the strongest.

[← Documentation home](../README.md)

| Level | You want to | You do | Effort |
| --- | --- | --- | --- |
| 1 | Change a value | Set a property, or the same value in Java | One line, or a small bean |
| 2 | Adjust a bean Stratum built | Declare a customizer bean | A few lines |
| 3 | Replace a bean entirely | Declare your own bean of the same type | You own that bean from now on |
| 4 | Turn a module on or off | `stratum.<module>.enabled=true` or `false` | One line |

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

### Set the same values in Java

Every value you can set in a file you can also set in Java code. This suits a service whose
configuration files would otherwise grow too large, or a value worked out in code. When a value is
set in both places, **the Java value wins**.

Each module binds its `stratum.<module>.*` properties to one settings type, a Java record with a
builder. To set values in Java, declare a bean of type `StratumPropertiesCustomizer<the settings
type>`. It receives the settings already read from your files, with Stratum's defaults for anything
you did not set, and returns the settings the module should use. Call `toBuilder()` on what it
receives, change only what you need, and `build()`: every other value is kept.

The logging module is the one exception: logging starts before Spring creates any bean, so its
`stratum.logging.*` values can be set only in files, environment variables or command-line
arguments. See [Logging → Configure in Java](../../starters/stratum-starter-logging/README.md#configure-in-java).

The settings types of the modules are planned; each module page will name its own. The example
below uses a module called `example` with two settings, `message` and `timeout`. The same values,
set three ways:

```properties
stratum.example.message=Hello
stratum.example.timeout=5s
```

```yaml
stratum:
  example:
    message: Hello
    timeout: 5s
```

```java
@Configuration
class ExampleConfig {

  @Bean
  StratumPropertiesCustomizer<ExampleProperties> exampleSettings() {
    return properties ->
        properties.toBuilder().message("Hello").timeout(Duration.ofSeconds(5)).build();
  }
}
```

With all three in place, the module uses the values from the Java bean. To set only the timeout in
Java and keep the message from your files, leave the message out:

```java
@Bean
StratumPropertiesCustomizer<ExampleProperties> exampleTimeout() {
  return properties -> properties.toBuilder().timeout(Duration.ofSeconds(5)).build();
}
```

Rules that apply to every module:

- Several customizers for the same settings run in `@Order` order. Each receives the result of the
  one before, so the last one wins.
- A customizer must not return `null`. To leave the settings unchanged, return what it received.
  A `null` stops the application at startup with a message naming the customizer.
- The settings are checked once, after your customizers have run. So a customizer can correct or
  supply a value the files leave invalid or unset. A bad value from either place, such as a blank
  message or a missing timeout, stops the application at startup. The message names the property
  key and the rule it breaks, never the value, which might be a secret.
- Declare the customizer with its settings type, as the return type of a `@Bean` method like the
  examples above. Spring uses that type to give each customizer to its own module. A customizer
  registered without it, for example with `registerBean(StratumPropertiesCustomizer.class, ...)`,
  stops the application at startup with a message naming it.

`StratumPropertiesCustomizer` is in `stratum-core`, in the package
`com.exepex.stratum.core.properties`. Every starter will bring it with it.

## Level 2: adjust a bean with a customizer

This is a different kind of customizer from the `StratumPropertiesCustomizer` of level 1, which
changes a module's settings before any bean is built. A **customizer** at this level is a small bean
that receives a builder or a bean Stratum created and changes part of it. Stratum keeps building the
bean; your customizer runs on top. You keep every other default.

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
of the same type, Stratum's version is not created and yours is used. The same holds for a bean from
your company's framework; see
[Using Stratum with a company framework](../guides/company-framework.md#rule-2-the-companys-bean-wins).

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

## Level 4: turn a module on or off

Every starter has a switch, and every module is **off until you switch it on**. Adding a starter, or
a [bundle](../modules/bundles.md), puts its libraries on the classpath; the switch decides whether
Stratum sets anything up. Switch on the modules your service uses:

```yaml
stratum:
  cache:
    enabled: true
```

```properties
stratum.cache.enabled=true
```

Leaving the property unset, or setting it to `false`, removes all of Stratum's beans and defaults for
the module. Spring Boot's own setup for the libraries the starter brings still applies, such as
Spring Security's default login; to avoid that, leave the starter out. Switching a module off is
useful for a test profile, or while you migrate one concern at a time. If you never want the
module, remove the starter from your `pom.xml`, or exclude it from the bundle, instead.

The switch is read from properties only, not from a `StratumPropertiesCustomizer`: Spring decides
which beans to create before any customizer runs.

A module's own switches, such as `stratum.web.correlation.enabled`, apply only once the module is
on. Their defaults, often `true`, mean that a module you switch on comes with all its parts.

Some modules also let you switch off one part:

| Property | Turns off |
| --- | --- |
| `stratum.web.request-logging.enabled=false` | Only the per-request log line |
| `stratum.observability.tracing.enabled=false` | Only tracing; metrics and health stay |

## Advanced: exclude one auto-configuration class

If you must, you can exclude a single Stratum auto-configuration class, as with any Spring Boot
auto-configuration:

```yaml
spring:
  autoconfigure:
    exclude:
      - com.exepex.stratum.web.autoconfigure.StratumCorrelationAutoConfiguration
```

```properties
spring.autoconfigure.exclude=com.exepex.stratum.web.autoconfigure.StratumCorrelationAutoConfiguration
```

Class names are internal and may change between minor versions. Prefer the `enabled` switches,
which are part of Stratum's public contract.

## Tell us when you override

If you find yourself overriding the same default in several services, the default is probably
wrong. Open an issue so it can be fixed once in Stratum, which is the whole point of having it.
