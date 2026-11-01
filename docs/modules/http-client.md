# HTTP client: `stratum-starter-http-client` and `stratum-starter-reactive-http-client`

**Status: Partly built.** Timeouts and a bounded connection pool are built for both stacks. Sending
the correlation ID and other headers on, turning timeouts into `503` and `504` answers, logging
failed calls, tokens and retries are **planned**.

This page covers what both starters share: how to pick one, every key a client needs, and the
defaults. The READMEs of [`stratum-starter-http-client`](../../starters/stratum-starter-http-client/README.md)
and [`stratum-starter-reactive-http-client`](../../starters/stratum-starter-reactive-http-client/README.md)
show a complete example for each stack.

[← Documentation home](../README.md) · Property prefix: `stratum.http-client`

## What it does

The most common production incident in microservices is a call that waits forever on a slow
downstream service. Spring Boot sets no timeout and no limit on connections, so one slow service
can use up every thread and socket of the services that call it. With these starters, every call
has a connect timeout and a read timeout, and the connections to each downstream service are
bounded. A service needs no configuration to be safe.

You keep writing Spring's own clients: an **HTTP interface** (a Java interface whose methods are
annotated with `@GetExchange`, `@PostExchange` and so on, which Spring implements for you), or a
`RestClient` or `WebClient` built from the builder Spring Boot provides. Stratum adds no client
type of its own.

## Pick your starter

| Your service | Add | Calls are sent by |
| --- | --- | --- |
| Uses `RestClient` or HTTP interfaces that return values (most services) | `stratum-starter-http-client` | Apache HttpClient 5 |
| Uses `WebClient` or HTTP interfaces that return `Mono` or `Flux` | `stratum-starter-reactive-http-client` | Reactor Netty |

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-starter-http-client</artifactId>
</dependency>
```

A service that makes both kinds of calls adds both. The [bundles](bundles.md) choose for you:
`stratum-starter-microservice` brings `stratum-starter-http-client`, and
`stratum-starter-reactive-microservice` brings `stratum-starter-reactive-http-client`.

Both starters are the module `http-client`. They do nothing until you switch them on, in
`application.properties`:

```properties
stratum.http-client.enabled=true
```

or in `application.yml`:

```yaml
stratum:
  http-client:
    enabled: true
```

Both read the same keys, so a service that moves from one stack to the other keeps its
configuration.

## Every key a client needs

A client's address, timeouts and fixed headers use **Spring Boot's own keys**, so anything you find
in Spring's documentation works as written. Stratum adds a `stratum.http-client.*` key only for
what Spring Boot has no key for, and gives Spring Boot's timeout keys safe defaults. The name after
`spring.http.serviceclient.` is the group name of your HTTP interface, from
`@ImportHttpServices(group = "inventory", ...)`.

This example sets every key, with Stratum's defaults where it has one. Copy it and keep only the
lines you need.

```properties
# Switch the module on.
stratum.http-client.enabled=true

# Every client: Spring Boot's keys, with Stratum's defaults.
spring.http.clients.connect-timeout=2s
spring.http.clients.read-timeout=5s

# Every client: the connection pool (Stratum's keys).
stratum.http-client.pool.max-connections=50
stratum.http-client.pool.max-wait=2s

# One client, named by its group: Spring Boot's keys.
spring.http.serviceclient.inventory.base-url=https://inventory.internal
spring.http.serviceclient.inventory.connect-timeout=1s
spring.http.serviceclient.inventory.read-timeout=10s
spring.http.serviceclient.inventory.default-header.X-Api-Version=2
```

```yaml
stratum:
  http-client:
    enabled: true
    pool:
      max-connections: 50
      max-wait: 2s

spring:
  http:
    clients:
      connect-timeout: 2s
      read-timeout: 5s
    serviceclient:
      inventory:
        base-url: https://inventory.internal
        connect-timeout: 1s
        read-timeout: 10s
        default-header:
          X-Api-Version: "2"
```

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `stratum.http-client.enabled` | boolean | `false` | Switches the module on. Until it is `true`, Spring Boot's clients keep Spring Boot's defaults: no timeouts and no pool limits. |
| `spring.http.clients.connect-timeout` | duration | `2s` (Stratum) | Time allowed to open a connection, for every client. |
| `spring.http.clients.read-timeout` | duration | `5s` (Stratum) | Time allowed to wait for data of the response, for every client. |
| `stratum.http-client.pool.max-connections` | int, 1 to 10000 | `50` | The most connections a pool keeps open at once to one downstream service (which clients share a pool is [below](#the-connection-pool)). A call that finds them all busy waits for one. |
| `stratum.http-client.pool.max-wait` | duration, more than 0 | `2s` | How long a call waits for a free connection before it fails. |
| `spring.http.serviceclient.<name>.base-url` | URL | none | The address of the downstream service. |
| `spring.http.serviceclient.<name>.connect-timeout` | duration | the one for every client | Time allowed to open a connection, for this client. |
| `spring.http.serviceclient.<name>.read-timeout` | duration | the one for every client | Time allowed to wait for data of the response, for this client. |
| `spring.http.serviceclient.<name>.default-header.<header>` | list of text | none | A fixed header sent on every call of this client. Not for secrets. |

Which value wins, from strongest to weakest:

1. The client's own `spring.http.serviceclient.<name>.*` key.
2. The `spring.http.clients.*` key you set.
3. Stratum's default.

Spring Boot has a few more keys for clients, such as `ssl.bundle`, `redirects` and `apiversion`.
They work as Spring Boot documents them; Stratum does not change them.

> [!TIP]
> Pick a read timeout from the downstream service's real latency: a little above its p99 latency is
> a good start. Then make sure the timeouts of your own callers are longer than yours.

A build test reads this page and fails if any Spring Boot key on it is removed or deprecated in a
new Spring Boot version, so the page cannot drift from Spring Boot.

## The connection pool

Each pool keeps at most `stratum.http-client.pool.max-connections` connections open at once to
one downstream service. A call that finds them all busy waits up to `stratum.http-client.pool.max-wait` for one, then
fails, so a slow downstream service makes calls to it fail fast instead of piling up.

| | Blocking (`stratum-starter-http-client`) | Reactive (`stratum-starter-reactive-http-client`) |
| --- | --- | --- |
| Pool | One pool per HTTP interface group, and one per `RestClient` built from Spring Boot's `RestClient.Builder`; each holds at most `max-connections` in all | One pool for every client; at most `max-connections` to each downstream service |
| A call that finds the pool full | Fails after `max-wait` with `ResourceAccessException`, caused by Apache's `ConnectionRequestTimeoutException` | Fails after `max-wait` with `WebClientRequestException`, caused by Reactor Netty's `PoolAcquireTimeoutException` |
| Pool metrics | **None.** Spring Boot builds Apache's pool without giving access to it, so nothing can read it. Each call's duration, from the observability starter, includes any time spent waiting for a connection. | Reactor Netty's `reactor.netty.connection.provider.*` gauges, such as `total.connections`, `active.connections` and `pending.connections`, tagged `name=stratum-http-client` and with the downstream address, when the service has Micrometer |

Every call is also traced and timed as `http.client.requests` by the
[observability starter](../../starters/stratum-starter-observability/README.md), on both stacks.

## Configure in Java

Every `stratum.http-client.*` setting can also be set in Java, and the Java value wins over the
files. Write a class that implements `HttpClientPropertiesCustomizer`. It receives the settings from
the files and returns them changed:

```java
package com.acme.orders.config;

import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;

public class OrdersHttpClientSettings implements HttpClientPropertiesCustomizer {

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    return properties.toBuilder()
        .pool(properties.pool().toBuilder().maxConnections(200).build())
        .build();
  }
}
```

List it in `src/main/resources/META-INF/spring.factories`:

```properties
com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer=\
com.acme.orders.config.OrdersHttpClientSettings
```

It is not a bean: Stratum reads it before any bean exists, the same way for every module. Declaring
it as a `@Bean` stops startup with `SX-CORE-1004`.

Spring Boot's keys, the timeouts and a client's address among them, are set in Java the way Spring
Boot documents it, for example through `SpringApplication.setDefaultProperties`; any value set
there wins over Stratum's defaults.

## Replace a bean

| Bean | Starter | What it does | To replace it |
| --- | --- | --- | --- |
| `RestClientConnectionPool` | `stratum-starter-http-client` | Sets the pool's size and wait on Spring Boot's builder of Apache HttpClient 5 clients | Declare your own bean of this type. Or declare a `ClientHttpRequestFactoryBuilderCustomizer` of your own with no order: it runs after Stratum's, so its pool settings win |
| `RestClientRetries` | `stratum-starter-http-client` | Stops Apache HttpClient 5 from sending a call again after a `429` or `503` answer, which it does by default even for a `POST`; Apache still resends an idempotent call when a connection drops | Declare your own bean of this type. Or declare a `ClientHttpRequestFactoryBuilderCustomizer` of your own with no order that sets a retry strategy: it runs after Stratum's, so it wins |
| `WebClientConnectionPool` | `stratum-starter-reactive-http-client` | Gives Spring Boot's Reactor Netty clients a bounded pool, and closes it at shutdown | Declare your own bean of this type. Or declare a `ClientHttpConnectorBuilderCustomizer` of your own with no order: it runs after Stratum's, so its pool wins |
| `WebClientGroupSettings` | `stratum-starter-reactive-http-client` | Makes each `WebClient` HTTP interface group use its own `spring.http.serviceclient.<name>.*` timeouts and SSL bundle (see below) | Declare your own bean of this type |

Spring Boot 4.1.2 loses a `WebClient` group's own timeouts and SSL bundle: it builds the group's
connector from them, then replaces it with the connector for every client, so a group's
`read-timeout` of `10s` is ignored and the `5s` for every client applies. Blocking groups are not
affected. `WebClientGroupSettings` builds the group's connector again from its own keys, the way
Spring Boot does, after Spring Boot has finished. It does this only for a group that sets a
timeout, `redirects`, `cookie-handling` or `ssl.bundle` of its own; a group with only an address or
headers keeps the connector it has. For such a group, a connector that your own `WebClientCustomizer`
sets is replaced, so change the connection with a `ClientHttpConnectorBuilderCustomizer` instead,
which Stratum keeps. A build test fails once a new Spring Boot version fixes this by itself, and
Stratum then removes the bean.

A service that picks another library with Spring Boot's `spring.http.clients.imperative.factory` or
`spring.http.clients.reactive.connector` keeps Stratum's timeouts, but not its pool: that library's
own pool rules apply.

## Planned

These are designed and will come in the next pull requests. None of them works yet.

| Feature | Behaviour |
| --- | --- |
| Correlation ID and other headers | `X-Correlation-Id` sent on every call. Other incoming headers are sent on only when listed in `stratum.http-client.forward-headers`, for every client or per client. `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization` and `X-Api-Key` can never be listed; listing one stops startup. |
| Timeouts as `503` and `504` | A call that times out or cannot connect throws `DownstreamUnavailableException`, with code `SX-HTTPC-0001` (timed out, answered `504`) or `SX-HTTPC-0002` (could not connect, answered `503`). The statuses can be changed with `stratum.web.error.statuses`. |
| Failed-call log | `stratum.http-client.call-log`: `none`, `errors` (the default: one `WARN` line per failed call) or `all`. Headers and bodies are never logged. |
| Tokens | Client credentials or token relay per client, through the [OAuth2 client](oauth2-client.md) starter. |
| Retries and circuit breakers | Per client, through the [resilience](resilience.md) starter. |

## Switch it on or off

`stratum.http-client.enabled=true` switches both starters on. Leaving it unset is the same as
`false`. When it is off, Spring Boot's HTTP client support stays as Spring Boot sets it up, with no
timeouts and no pool limits from Stratum.

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| A call fails after exactly 5s | The default read timeout. Raise `spring.http.serviceclient.<name>.read-timeout` for that client if the downstream service is legitimately slow. |
| Calls fail after 2s with `ConnectionRequestTimeoutException` or `PoolAcquireTimeoutException` | Every connection to that downstream service was busy for `pool.max-wait`. Usually the downstream service is slow; otherwise raise `stratum.http-client.pool.max-connections`. |
| Startup fails: `SX-CORE-1003: Invalid settings: stratum.http-client.pool.max-connections` | The pool size is outside 1 to 10000. |
| A client has no timeouts | The module is off, or the client was built with `RestClient.create()` or `WebClient.create()` instead of the builder Spring Boot provides. |
