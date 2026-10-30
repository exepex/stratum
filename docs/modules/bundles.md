# Bundle starters: one dependency per kind of application

**Status: Built as POMs, not released.** The five bundles exist and pass `mvn verify`. Of the
single starters they list, logging and observability are built. The web starters bring their Spring
web stack, Spring MVC or WebFlux, so a bundle starts a web server, but Stratum's own web features
are still planned. The other starters are planned and add nothing yet.

[← Documentation home](../README.md) · Decision record: [ADR 0003](../adr/0003-bundle-starters-and-module-switches.md)

## What a bundle is

A **bundle starter** is a dependency with no code of its own. It lists the single starters that one
kind of application needs, so your service adds one dependency instead of six. It works the same
whichever parent your service uses: `stratum-parent`, or your company's parent with `stratum-bom`
imported ([Installation](../getting-started/installation.md)).

Each bundle lists only what that kind of application needs, so nothing unwanted lands on your
classpath. A single starter never pulls in another unrelated starter.

## The bundles

| Bundle | For | Single starters it adds |
| --- | --- | --- |
| `stratum-starter-microservice` | A REST service that other services call | webmvc, logging, observability, http-client, resilience, security |
| `stratum-starter-reactive-microservice` | The same, written with WebFlux (`Mono` and `Flux`) | webflux, logging, observability, http-client, resilience, security |
| `stratum-starter-web-ui` | A server-rendered web application | webmvc, logging, observability, security |
| `stratum-starter-batch` | A batch job that reads and writes a database | data-jpa, logging, observability |
| `stratum-starter-cli` | A command-line application | logging |

The single starters are described on their own pages, linked from the
[documentation home](../README.md#modules).

## Add a bundle

No `<version>` tag: the parent or the BOM sets it.

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-starter-microservice</artifactId>
</dependency>
```

A bundle only puts the starters on your classpath. Every module stays **off until you switch it
on**, so switch on the ones your service uses:

```properties
stratum.web.enabled=true
stratum.logging.enabled=true
stratum.observability.enabled=true
```

```yaml
stratum:
  web:
    enabled: true
  logging:
    enabled: true
  observability:
    enabled: true
```

Modules you leave unset stay off and set nothing up. Some modules need one more setting once they
are on, such as the observability module's Collector addresses
([installation](../getting-started/installation.md)).

> [!WARNING]
> Once the security starter is built (it is planned), the microservice, reactive microservice and web-ui bundles will bring
> Spring Security with it. While `stratum.security.enabled` is unset, Spring Boot's default will
> apply and every endpoint will ask for a login. Switch security on and set its issuer, as
> [Security](security.md) shows, or exclude the starter as in
> [Leave a starter out](#leave-a-starter-out).

## Add more starters

A bundle is a starting point. Add any single starter next to it, for example caching:

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-starter-microservice</artifactId>
</dependency>
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-starter-cache</artifactId>
</dependency>
```

## Leave a starter out

To keep one of a bundle's starters off your classpath entirely, exclude it. Here the service keeps
its company's security and drops Stratum's:

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-starter-microservice</artifactId>
  <exclusions>
    <exclusion>
      <groupId>com.exepex.stratum</groupId>
      <artifactId>stratum-starter-security</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```

To keep a starter on the classpath without Stratum setting anything up, leave its switch unset.
Spring Boot's own setup for the libraries the starter brings still applies, such as Spring
Security's default login; to avoid that, exclude the starter as above. See
[Turn a module on or off](../configuration/overriding-defaults.md#level-4-turn-a-module-on-or-off).

## Pick single starters instead

You do not have to use a bundle. A service can list exactly the single starters it wants, as
[Installation](../getting-started/installation.md#step-2-add-the-starters-you-need) shows.
