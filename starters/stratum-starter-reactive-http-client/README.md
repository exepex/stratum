# Reactive HTTP client: `stratum-starter-reactive-http-client`

**Status: Partly built.** Every reactive call to another service has a connect timeout and a read
timeout, and the connections to each downstream service are bounded and reported, so a slow
downstream service cannot hang your service. Sending headers on, `503` and `504` answers for
timeouts, a log line for failed calls, tokens and retries are **planned**.

This page covers this starter only. Every key, the defaults and the blocking starter are in
[HTTP client](../../docs/modules/http-client.md). How Stratum works as a whole is in the
[documentation](../../docs/README.md).

[← Documentation home](../../docs/README.md) · Property prefix: `stratum.http-client`

## Add it

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-starter-reactive-http-client</artifactId>
</dependency>
```

It brings Spring's `WebClient` and Reactor Netty, which sends the calls. Use this starter when your
clients return `Mono` or `Flux`; a service whose code waits for each answer uses
[`stratum-starter-http-client`](../stratum-starter-http-client/README.md) instead, and a service
that does both adds both. The `stratum-starter-reactive-microservice` bundle already includes this
starter. The version comes from Stratum's parent or BOM
([installation](../../docs/getting-started/installation.md)).

## A complete example

**1. Describe the downstream API as an interface** whose methods return `Mono` or `Flux`. Spring
writes the code that calls it.

```java
package com.acme.orders.inventory;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import reactor.core.publisher.Mono;

public interface InventoryClient {

  @GetExchange("/stock/{sku}")
  Mono<Stock> stockFor(@PathVariable String sku);

  record Stock(String sku, int available) {}
}
```

**2. Register it in a named group that uses `WebClient`.** The group name is how the configuration
finds it.

```java
package com.acme.orders.inventory;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.ImportHttpServices;

@Configuration
@ImportHttpServices(
    group = "inventory",
    types = InventoryClient.class,
    clientType = HttpServiceGroup.ClientType.WEB_CLIENT)
class InventoryClientConfig {}
```

**3. Switch the module on and give the group its address**, in `application.properties`:

```properties
stratum.http-client.enabled=true
spring.http.serviceclient.inventory.base-url=https://inventory.internal
# Optional: this client may take longer than the 5s default.
spring.http.serviceclient.inventory.read-timeout=10s
```

or in `application.yml`:

```yaml
stratum:
  http-client:
    enabled: true

spring:
  http:
    serviceclient:
      inventory:
        base-url: https://inventory.internal
        # Optional: this client may take longer than the 5s default.
        read-timeout: 10s
```

**4. Inject the interface and call it:**

```java
@Service
class StockCheck {

  private final InventoryClient inventoryClient;

  StockCheck(InventoryClient inventoryClient) {
    this.inventoryClient = inventoryClient;
  }

  Mono<Boolean> inStock(String sku) {
    return inventoryClient.stockFor(sku).map(stock -> stock.available() > 0);
  }
}
```

A plain `WebClient` gets the same timeouts and pool when you build it from the `WebClient.Builder`
that Spring Boot provides; inject the builder instead of calling `WebClient.create()`.

## What you get

| Feature | Behaviour |
| --- | --- |
| Timeouts | Connect `2s` and read `5s` for every client, unless you set others with `spring.http.clients.*` or per client with `spring.http.serviceclient.<name>.*` |
| Connection pool | One pool for every client, with at most `50` connections to each downstream service; a call that finds them all busy fails after `2s` with `WebClientRequestException` |
| Pool metrics | Reactor Netty's `reactor.netty.connection.provider.*` gauges, tagged `name=stratum-http-client`, when the service has Micrometer, as the [observability starter](../stratum-starter-observability/README.md) brings it |
| Tracing and timings | A client span and an `http.client.requests` timing per call, from the observability starter |

Every key, with its default, is in [Every key a client needs](../../docs/modules/http-client.md#every-key-a-client-needs).
Changing the pool from Java is in [Configure in Java](../../docs/modules/http-client.md#configure-in-java),
and replacing Stratum's bean in [Replace a bean](../../docs/modules/http-client.md#replace-a-bean).

## Switch it on or off

`stratum.http-client.enabled=true` switches it on. Leaving it unset is the same as `false`: Spring
Boot's clients then have no timeouts, and Reactor Netty's own pool is used.
