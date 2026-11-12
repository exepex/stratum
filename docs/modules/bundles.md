# Bundle starters: one dependency per kind of application

**Status: Built as POMs, released in 0.1.0.** The five bundles are on Maven Central from version 0.1.0.
A bundle lists only single starters that have code: the web and cache starters are built, and the logging,
observability and HTTP client starters are partly built, as their pages say. The web starters bring
their Spring web stack, Spring MVC or WebFlux, so a bundle starts a web server. The security,
resilience and data-jpa starters join no bundle until they are built.

[← Documentation home](../README.md) · Decision records: [ADR 0003](../adr/0003-bundle-starters-and-module-switches.md),
[ADR 0010](../adr/0010-modules-on-when-present-and-one-compatibility-switch.md),
[ADR 0019](../adr/0019-one-parent-per-kind-of-application.md)

## What a bundle is

A **bundle starter** is a dependency with no code of its own. It lists the single starters that one
kind of application needs, so your service adds one dependency instead of six. It works the same
whichever parent your service uses: `stratum-parent`, or your company's parent with `stratum-bom`
imported ([Installation](../getting-started/installation.md)). `stratum-microservice-parent` brings the
microservice bundle for you.

Each bundle lists only what that kind of application needs, so nothing unwanted lands on your
classpath. A single starter never pulls in another unrelated starter.

## The bundles

| Bundle | For | Single starters it adds |
| --- | --- | --- |
| `stratum-starter-microservice` | A REST service that other services call | webmvc, logging, observability, http-client, cache |
| `stratum-starter-reactive-microservice` | The same, written with WebFlux (`Mono` and `Flux`) | webflux, logging, observability; the reactive HTTP client starter joins it once built (planned) |
| `stratum-starter-web-ui` | A server-rendered web application | webmvc, logging, observability |
| `stratum-starter-batch` | A batch job | logging, observability |
| `stratum-starter-cli` | A command-line application | logging |

The single starters are described on their own pages, linked from the
[documentation home](../README.md#modules).

With the microservice bundle, `@Cacheable` methods are cached in Caffeine, in the service's own
memory, with Stratum's defaults: at most 10,000 entries per cache, each living 10 minutes, with hits
and misses measured ([Caffeine](cache-caffeine.md)). Add Spring Boot's Redis starter and Spring Boot
uses Redis instead ([Cache](cache.md#add-it)).

## Add a bundle

With `stratum-microservice-parent` as your parent, you do not add the microservice bundle: the parent
already brings it, with the test libraries
([The microservice parent](parent-and-bom.md#the-microservice-parent)). Under any other parent, add the
bundle for your kind of application. No `<version>` tag: the parent or the BOM sets it.

```xml
<dependency>
  <groupId>io.github.exepex.stratum</groupId>
  <artifactId>stratum-starter-microservice</artifactId>
</dependency>
```

A bundle puts the starters on your classpath, and every module it brings is **on**, with no line
of configuration. Turn off a module your service does not use with `stratum.<module>.enabled=false`,
for example the observability module:

```properties
stratum.observability.enabled=false
```

```yaml
stratum:
  observability:
    enabled: false
```

Some modules need one more setting to start, such as the logging module's
`spring.application.name` and the observability module's Collector addresses
([installation](../getting-started/installation.md)). Without it, startup stops with an error that
names the missing key and `stratum.<module>.enabled=false`
([error codes](../guides/error-codes.md)); a module never switches itself off quietly.

Adding a starter to a bundle turns its module on in every service that uses the bundle, so it goes
in the release notes as a breaking change.

No bundle brings the security starter yet, because it is planned. Once it is built and joins a
bundle, that bundle will bring Spring Security with the security module on, and the release notes
will say so.

## Add more starters

A bundle is a starting point. Add any single starter next to it, for example caching in a
reactive service:

```xml
<dependency>
  <groupId>io.github.exepex.stratum</groupId>
  <artifactId>stratum-starter-reactive-microservice</artifactId>
</dependency>
<dependency>
  <groupId>io.github.exepex.stratum</groupId>
  <artifactId>stratum-starter-cache</artifactId>
</dependency>
```

## Leave a starter out

To keep one of a bundle's starters off your classpath entirely, exclude it. Here the service keeps
its company's caching and drops Stratum's. With `stratum-microservice-parent`, declare the bundle
again in the same way: your own declaration, with its exclusion, replaces the parent's.

```xml
<dependency>
  <groupId>io.github.exepex.stratum</groupId>
  <artifactId>stratum-starter-microservice</artifactId>
  <exclusions>
    <exclusion>
      <groupId>io.github.exepex.stratum</groupId>
      <artifactId>stratum-starter-cache</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```

To keep a starter on the classpath without Stratum setting anything up, set its switch to `false`,
such as `stratum.cache.enabled=false`.
Spring Boot's own setup for the libraries the starter brings still applies, such as Spring Boot's
Caffeine caches, with no bound, for a service with its own `@EnableCaching`; to avoid that, exclude
the starter as above. See
[Turn a module off](../configuration/overriding-defaults.md#level-4-turn-a-module-off).

## Pick single starters instead

You do not have to use a bundle. A service can list exactly the single starters it wants, as
[Installation](../getting-started/installation.md#step-2-add-the-starters-you-need) shows.
