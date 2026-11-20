# What your caller learns

When a request to your service fails, its caller gets a status and a code. This page lists what each failure that
Stratum answers for you tells the caller, whether a caller sends the request again because of it, and whether the work
may already be done. It also says what a job, a message listener or a command-line application sees, as they have no
caller to answer.

[← Documentation home](../README.md) · [Error and warning codes](error-codes.md) ·
[HTTP client: errors](../modules/http-client-errors.md) · [Web: database failures](../modules/web-database-errors.md)

## The catalogue

Each status below is Stratum's default. You change any of them in `stratum.web.error.statuses`, as the
[web module](../modules/web.md#properties) shows; read the column "Sent again" first.

"Sent again" says what a Stratum HTTP client in the calling service does with that status: it sends a `GET`, `HEAD`,
`OPTIONS`, `PUT` or `DELETE` again after a `502`, `503`, `504` or `429`, and never after a `500` or a `409`. A `POST`
or a `PATCH` is not sent again after any answer ([ADR 0011](../adr/0011-http-client-starters.md), decision 4.2).
Gateways and other client libraries have their own rules, which Stratum does not check.

"Work may be done" says whether the work the request asked for may have happened, though the request failed.

| What happened | Code | Status | Sent again | Work may be done |
| --- | --- | --- | --- | --- |
| Another service did not answer in time, after the client's retries | `SX-HTTPC-0001` | `504` | yes | yes: the call was sent |
| Another service could not be reached: refused, unknown host, a full pool, a connect timeout, or no token for it | `SX-HTTPC-0002` | `503` | yes | no: nothing was sent |
| The connection to another service dropped after the call was sent | `SX-HTTPC-0002` | `503` | yes | yes |
| Another service's circuit breaker is open, so the call was not sent | `SX-HTTPC-0003` | `503` | yes | no |
| A client that relays the caller's token has none | `SX-HTTPC-0004` | `500` | no | no |
| An interceptor read a request body over `interceptor-body-limit` | `SX-HTTPC-0005` | `500` | no | no |
| Another service's successful answer is over `response-body-limit` | `SX-HTTPC-0006` | `500` | no | yes |
| Another service answered with a final error, such as `400`, `404`, `409` or `500` | `SX-HTTPC-0007` | `500` | no | not as a rule; a `5xx` may follow part of the work |
| Another service's answer cannot be read | `SX-HTTPC-0008` | `500` | no | yes |
| Another service still answered `429`, `502`, `503` or `504` after the client's retries | `SX-HTTPC-0009` | `500` | no | yes after a `502` or `504` from a gateway in front of it |
| Another service answered with an error status the client's `errors` maps to your own code | your code, such as `ORD-0409` | the status you give your code, else `500` | as you choose | as for `SX-HTTPC-0007` or `SX-HTTPC-0009` |
| Your own request ran out of its time limit before its response started | `SX-WEB-1007` | `503` | yes | yes |
| The database was busy or too slow (outside [backward-compatible mode](../modules/web-compatible-mode.md)) | `SX-WEB-1010` | `503` | yes | not when its transaction was rolled back |
| The request conflicts with the stored data (outside [backward-compatible mode](../modules/web-compatible-mode.md)) | `SX-WEB-1011` | `409` | no | no |
| Anything else your service did not handle | `SX-WEB-1005` | `500` | no | yes |

The codes `SX-HTTPC-0001` to `SX-HTTPC-0009`, and your own codes from a client's `errors`, come from calls of a
`@StratumClient` interface only, as [Scope](#scope) says.

### Why another service's failure is answered with `500`

When another service answers `503`, your service has already sent the call again, as its client's retries allow. If
your service passed `503` on, its own caller would send the request again too, and each service in a chain would
multiply the calls that reach the last one. A caller would also read "send it again" for a payment whose `502` from a
gateway may mean it was done. So a downstream error answer, of any status, is answered with `500`, and the code tells
your caller what happened: `SX-HTTPC-0009` for "it cannot take the call for now", `SX-HTTPC-0007` for a final answer.
Stratum never passes another service's status, headers or body on to your caller. Only a status you list in a
client's `errors` gets your own code instead, with the status you give that code
([HTTP client: errors](../modules/http-client-errors.md#your-own-code-for-an-error-status)).

If your callers need a status that sends a request again, such as `503` for `SX-HTTPC-0009`, set it for that code,
and keep it only for requests that are safe to send twice:

```yaml
stratum:
  web:
    error:
      statuses:
        SX-HTTPC-0009: 503
```

```properties
stratum.web.error.statuses.SX-HTTPC-0009=503
```

In Java, set it in a `WebPropertiesCustomizer`, as [HTTP client: errors](../modules/http-client-errors.md) shows.

Your own `@RestControllerAdvice` always wins: an exception it answers gets its answer, whatever the table says.

## Outside a web request

A scheduled job, an `@Async` method, a message listener, a batch step or a command-line application gets the
exception itself. Each exception of a failed client call carries its code through Stratum's `FailureWithErrorCode`,
so `getErrorCode()` tells a failure that may pass from one that will not:

| Exception | Code | May pass if tried later |
| --- | --- | --- |
| `StratumException` | `SX-HTTPC-0001`, `SX-HTTPC-0002`, `SX-HTTPC-0003` | yes |
| `DownstreamException` | `SX-HTTPC-0009` | yes; `getResponseHeaders()` holds the other service's `Retry-After`, if it sent one |
| `DownstreamException` | `SX-HTTPC-0007` | no |
| `DownstreamException` | your own code for a status the client's `errors` maps, such as `ORD-0409` | as the status says: a mapped `429`, `502`, `503` or `504` no longer reads as `SX-HTTPC-0009`, so test for your code too |
| `UnreadableAnswerException`, a Spring `RestClientException` | `SX-HTTPC-0008` | no |
| `StratumException` | `SX-HTTPC-0004`, `SX-HTTPC-0005`, `SX-HTTPC-0006` | no |

```java
try {
  inventory.reserve(order);
} catch (DownstreamException | StratumException failure) {
  var code = ((FailureWithErrorCode) failure).getErrorCode().getCode();
  if (code.equals("SX-HTTPC-0009")) {
    throw new RetryLaterException(failure); // your listener or job tries again later
  }
  throw failure;
}
```

Database failures outside a web request arrive as Spring's and the JDBC driver's own exceptions, not as
`SX-WEB-1010` or `SX-WEB-1011`, which only the web module gives. The ones that may pass are those
[Web: database failures](../modules/web-database-errors.md#which-failures-get-which-answer) answers with `503`:
`java.sql.SQLTimeoutException`, `java.sql.SQLTransientConnectionException`, Spring's `TransactionTimedOutException`
and SQLStates `57014`, `55P03` and `40P01`. Spring's `OptimisticLockingFailureException`, Hibernate's
`StaleStateException` and SQLState `23505` are conflicts: read the data again before you try again. With a data
starter, a statement Stratum cancelled at its time limit is `SX-DATA-1003`.

A code that ends the run of a command-line application is reported by Spring Boot as `Application run failed`, with
the stack trace, never as a startup failure ([Error and warning codes](error-codes.md)).

## Outcome unknown

For these failures the other service may have done the work, though your call failed:

- `SX-HTTPC-0001`: the call was sent, and no answer came in time;
- `SX-HTTPC-0002` after the connection dropped once the call was sent;
- `SX-HTTPC-0009` from a `502` or `504`, which a gateway in front of the other service may send after the service
  did the work;
- `SX-HTTPC-0008`: the other service answered, but its answer cannot be read.

Before a write is sent again, check whether the first one was done, such as by reading the order, or give the other
service a key it uses to ignore a second copy of the same request. Stratum does not add such keys for you. A message
listener's redelivery and a batch step's retry of a chunk send the write again too: they need the same check.

## Timeouts in a chain

When service A calls B, and B calls C, give each inner call less time than the call around it, so that an inner call
ends, and its failure is answered, before the outer caller gives up:

- B's `stratum.http-client.clients.<c>.total-timeout` is less than A's `total-timeout` for B, with room for B's own
  work;
- B's own time limit, `stratum.web.timeout.default-limit` or a route's, is not more than A's `total-timeout` for B.

Otherwise A gives up with `SX-HTTPC-0001` while B still waits for C, and the work at C may still be done. The client's
settings are on [HTTP client](../modules/http-client.md#properties) and the time limits on
[Web: response timeouts](../modules/web-timeouts.md).

## Scope

The codes `SX-HTTPC-0007` to `SX-HTTPC-0009`, `DownstreamException` and `UnreadableAnswerException` come from calls of
a `@StratumClient` interface, or of an interface joined to a client through its `interfaces` setting. A call through
the client's `RestClient` or `RestTemplate` bean, or `stratumHttpClients.restClient("<name>")`, throws Spring's own
exceptions, such as `HttpClientErrorException`, as a client you built would
([HTTP client: clients Stratum did not build](../modules/http-client-own-clients.md)).
