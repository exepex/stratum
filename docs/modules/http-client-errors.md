# HTTP client: errors

What a call of a `@StratumClient` interface throws when it fails, after its retries, and what the caller of your
service then gets. Part of the [HTTP client](http-client.md) page.

[← HTTP client](http-client.md) · Design: [ADR 0011](../adr/0011-http-client-starters.md), decision 6

## What a failed call throws

| The call | It throws | Your caller gets, if you do not catch it |
| --- | --- | --- |
| Got no answer in time: the answer, or a byte of it, took longer than `timeout` | `StratumException` with `SX-HTTPC-0001` | `504` |
| Got no answer for another reason: the connection was refused or timed out, the host is unknown, the pool stayed full for `max-wait`, or the connection dropped | `StratumException` with `SX-HTTPC-0002` | `503` |
| Was not sent, as the client's circuit breaker is open | `StratumException` with `SX-HTTPC-0003` | `503` |
| Got an error answer, `4xx` or `5xx` | `DownstreamException`, or your own exception from `mapError` | `500` |
| Got an answer whose body cannot be read, such as JSON that does not match | Spring's `RestClientException`, as without Stratum | `500` |

The three codes say only what went wrong, such as "The other service did not answer in time". The body your caller
gets never names the other service, its address or its path. Your own log does: the code's cause, Spring's
`ResourceAccessException`, carries the address in the stack trace the web starter logs.

The statuses are Stratum's defaults in the web starter's `stratum.web.error.statuses`. You change one the way you map
your own codes, and your value wins:

```yaml
stratum:
  web:
    error:
      statuses:
        SX-HTTPC-0002: 502
```

```properties
stratum.web.error.statuses.SX-HTTPC-0002=502
```

Even in [backward-compatible mode](web-compatible-mode.md), a call that ends with a code is answered with Stratum's
error body, as every `StratumException` is.

## `DownstreamException`

It extends Spring's `RestClientResponseException`, so `getStatusCode()`, `getResponseHeaders()`,
`getResponseBodyAsString()` and `getResponseBodyAs(Class)` work as on Spring's. It adds:

| Method | Returns |
| --- | --- |
| `getClient()` | The client's name, such as `inventory`. |
| `getEndpoint()` | The method and URI template, such as `GET /stock/{sku}`, never a path or query value. |
| `getTraceId()` | The trace ID of the call, to find its logs; empty when none was being logged. |
| `getDownstreamCode()` | The `code` of an `application/problem+json` body, such as another Stratum service's `ORD-0001`; empty for any other body. |

Its message is `inventory answered 404 to GET /stock/{sku}`. It never holds the body or a path or query value, so
logging it leaks no data. Uncaught, your caller gets `500` and Stratum's body, never the other service's body.

A client you move to Stratum no longer throws Spring's subtypes, such as `HttpClientErrorException.NotFound`. Catch
`DownstreamException` and check the status instead:

```java
try {
  return inventory.stock(sku);
} catch (DownstreamException failure) {
  if (failure.getStatusCode().value() == 404) {
    return Stock.none(sku);
  }
  throw failure;
}
```

## Your own exception for an error answer

To turn one client's error answer into your own exception everywhere, throw it from `mapError` in a
`StratumClientCustomizer`; return to keep the `DownstreamException`. It is asked on each call that ends with an error
answer, in the customizers' order, so the first that throws wins. The three codes are never passed to it.

```java
@Bean
StratumClientCustomizer stockNotFound() {
  return new StratumClientCustomizer() {
    @Override
    public void customize(String client, RestClient.Builder builder) {
      // Nothing to change on the builder.
    }

    @Override
    public void mapError(String client, DownstreamException failure) {
      if (client.equals("inventory") && failure.getStatusCode().value() == 404) {
        throw new StockNotFoundException(failure);
      }
    }
  };
}
```

## The error body is read up to 64 KB

Of an error answer's body, at most 64 KB is read and kept, after gzip is undone, so a huge error body never fills the
memory. An answer that succeeds is read whole. The limit has no key. The rest of a longer error body is read and
dropped when the answer closes, so the connection can be used again; a body that never ends is bounded only by
`timeout` between bytes ([#236](https://github.com/exepex/stratum/issues/236)).

## Clients Stratum did not build

A call through `stratumHttpClients.restClient("<name>")` throws Spring's own exceptions, as a `RestClient` you built
would ([ADR 0012](../adr/0012-http-client-changes-found-while-building.md)). Its error body is still read up to
64 KB.

## Startup errors

| Code | When |
| --- | --- |
| `SX-HTTPC-1001` | A `spring.http.serviceclient.<name>.*` key is set for a Stratum client. |
| `SX-HTTPC-1002` | A client method returns `Mono` or `Flux`. |
| `SX-HTTPC-1003` | A client name is not lowercase letters, digits and hyphens starting with a letter. |
| `SX-HTTPC-1004` | A client has no `url`. |
| `SX-HTTPC-1007` | A client's `interfaces` names a type that is not an interface on the classpath. |
| `SX-HTTPC-1008` | An interface marked `@StratumClient` for one client is named in another client's `interfaces`. |

Each code, with its fix, is in [Error codes](../guides/error-codes.md#stratum-starter-http-client).
