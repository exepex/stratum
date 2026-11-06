# Observability: observing your service's stack

This page belongs to the [observability starter](../../starters/stratum-starter-observability/README.md).
It takes a typical service, which checks JWT access tokens from an identity provider such as Okta,
stores data in PostgreSQL, caches in Redis and sends JMS messages, and says what you see for each
part: its metrics, its spans, and the switch that turns them off. Every gap named here is also on
[known gaps and backends](observability-known-gaps.md).

Most of it comes from Spring Boot, with nothing to write. Stratum's part is to send it all to the
Collector, keep IDs out of metric tags ([tag guard](../../starters/stratum-starter-observability/README.md#tag-guard))
and keep secrets off spans.

## Incoming requests and JWT access tokens

| What | Where it comes from |
| --- | --- |
| `http.server.requests` timer, per `uri` template, status, exception and [error code](observability-traces.md#error-codes-on-requests) | Spring Boot, with a web starter |
| A server span per request, with the `uri` template and the path, never the query | Spring Boot |
| Spring Security spans: `security filterchain before`, `security filterchain after`, `authenticate bearertoken`, `authorize request` and `secured request` | Spring Security, whenever it is on the class path |

These were checked with Spring MVC, an OAuth2 resource server and a stub token issuer, not with
Okta itself; a check with Okta is planned ([#233](https://github.com/exepex/stratum/issues/233)).
No span carried the token, the token's subject, a cookie or a query value. The authentication span
carries the authorities granted, such as `FACTOR_BEARER`, never the token's claims. A request
refused with `401` before it reaches a controller has `uri=UNKNOWN`, so every refused request shares
one series.

To log who called, turn on the request log's [token claims](web.md#token-claims), which are off by
default. To turn the Spring Security spans off, use Spring Boot's switch for every observation
whose name starts with `spring.security` (a test of it is planned,
[#235](https://github.com/exepex/stratum/issues/235)):

```properties
management.observations.enable.spring.security=false
```

```yaml
management:
  observations:
    enable:
      "[spring.security]": false
```

## PostgreSQL

Database queries make no spans yet: a request's span shows the time spent, not which query took it
([#225](https://github.com/exepex/stratum/issues/225)). Until then, these metrics from Spring Boot
show where database time goes:

| Metric | What it shows |
| --- | --- |
| `hikaricp.connections.active`, `.idle`, `.pending`, `.max`, `.min` | The connection pool's state, tagged with the pool's name. `pending` above zero means callers wait for a connection |
| `hikaricp.connections.acquire`, `.usage`, `.creation` | How long getting, holding and opening a connection take |
| `hikaricp.connections.timeout` | Callers that gave up waiting, after `spring.datasource.hikari.connection-timeout` ([data](data-jpa.md)) |
| `spring.data.repository.invocations` | Each Spring Data repository call, tagged with the repository, the method, its state and exception |

The database health check `db` is in `/actuator/health`. To turn the metrics off:

```properties
management.metrics.enable.hikaricp=false
management.metrics.data.repository.autotime.enabled=false
```

```yaml
management:
  metrics:
    enable:
      hikaricp: false
    data:
      repository:
        autotime:
          enabled: false
```

## Redis

With Spring Boot's default client, Lettuce, each Redis command makes a span, such as `get` or
`set`, with `db.system=redis`, the command in `db.operation`, and the server's address and port.
The command's key and value, and the password, are not on it. With the
[cache starter](cache.md), a command for one of your caches also carries the cache's name
([cache traces](cache-traces.md)). The `lettuce` timer times each command, tagged with the command
and the server's address and port.

| Metric | Where it comes from |
| --- | --- |
| `cache.gets` (hits and misses), `cache.puts`, `cache.removals`, per cache | Spring Boot; Redis caches count hits and misses because the cache starter turns their statistics on ([cache metrics](cache.md#metrics)) |
| `stratum.cache.redis.available`, `stratum.cache.failures`, `stratum.cache.skips`, `stratum.cache.redis.connect-failures` | The cache starter ([cache metrics](cache.md#metrics)) |

The Redis health check `redis` is in `/actuator/health`. The cache metrics follow
`management.metrics.enable.cache`, like any Spring Boot metric.

## JMS

Each send and each handled message is timed (`jms.message.publish`, `jms.message.process`) and
traced, for the JMS beans Spring Boot builds and for the ones your service builds itself; the
consumer's span joins the sender's trace. The timers do not carry the queue name; the spans do.
A listener that throws records the exception's class in the `error` tag. All of this, and how to
turn it off, is on [messages](observability-messages.md).

The JMS connection pool has no metrics, and Stratum tests JMS with ActiveMQ Artemis only, not with
TIBCO EMS ([#226](https://github.com/exepex/stratum/issues/226)). The JMS health check `jms` is in
`/actuator/health`.

## Counting active users

A user ID never goes on a metric: each user would make a new series, and the
[tag guard](../../starters/stratum-starter-observability/README.md#tag-guard) drops a metric tagged
`userId`. Count users in two other ways:

- **Logged-in and active users** come from the log backend. Once each request's log lines carry an
  opaque user ID, a random ID that names no person, count its distinct values over a day or an hour
  there, such as Kibana's *Unique Count* or a `count by` in Loki. That log field comes with the
  security starter, which is **planned** ([security](security.md)).
- **Usage per client, tenant or role** comes from a counter of your own, whose tag holds one of a
  small, known set of values:

```java
@Component
class OrderUsage {

  private final MeterRegistry meterRegistry;

  OrderUsage(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  void orderPlaced(Jwt token) {
    String client = Objects.requireNonNullElse(token.getClaimAsString("azp"), "unknown");
    meterRegistry.counter("orders.placed", "client", client).increment();
  }
}
```

The `azp` claim names the client application, such as `checkout-web`, so the tag has as many values
as you have clients, plus `unknown` for a token without it. Never tag with the subject or an email address.
