# HTTP client: errors

What a call of a `@StratumClient` interface throws when it fails, after its retries, and what the caller of your
service then gets. Part of the [HTTP client](http-client.md) page.

[← HTTP client](http-client.md) · Design: [ADR 0031](../adr/0031-http-client-errors-and-body-limits.md), decisions 1 and 2

## What a failed call throws

| The call | It throws | Your caller gets, if you do not catch it |
| --- | --- | --- |
| Got no answer in time: the answer, or a byte of it, took longer than `read-timeout`, or its body was still arriving when `total-timeout` ran out | `StratumException` with `SX-HTTPC-0001` | `504` |
| Got no answer for another reason: the connection was refused or timed out, the host is unknown, the pool stayed full for `max-wait`, or the connection dropped; or, with `auth.client-credentials`, the client's token cannot be fetched and the call is not sent | `StratumException` with `SX-HTTPC-0002` | `503` |
| Was not sent, as the client's circuit breaker is open | `StratumException` with `SX-HTTPC-0003` | `503` |
| Was not sent, as a client with `auth.token-relay: true` has no verified caller token ([auth](http-client-auth.md#token-relay)) | `StratumException` with `SX-HTTPC-0004` | `500` |
| Was not sent, as an interceptor read a body larger than the client's `interceptor-body-limit` ([bodies](http-client-headers.md#bodies)) | `StratumException` with `SX-HTTPC-0005` | `500` |
| Got a `404`, and its method returns `Optional` | Nothing: the method returns an empty `Optional` | no error |
| Got `429`, `502`, `503` or `504` after its retries: the other service cannot take the call for now | `DownstreamException` with `SX-HTTPC-0009`, or with your own code from `errors`, or your own exception from `mapError` | `500`, or the status you give your own code |
| Got any other error answer, `4xx` or `5xx`, such as `400`, `404` or `500`: a final answer | `DownstreamException` with `SX-HTTPC-0007`, or with your own code from `errors`, or your own exception from `mapError` | `500`, or the status you give your own code |
| Got an answer whose body cannot be read, such as JSON that does not match or ends too soon, with Jackson 3, Jackson 2's converter or Gson's, bytes that are not valid protobuf, or a content type no converter reads. The other service may have done the work. It is never sent again and never counts for the circuit breaker. A body cut off by the network is not this case: it ends with `SX-HTTPC-0001` or `SX-HTTPC-0002` | `UnreadableAnswerException` with `SX-HTTPC-0008`, a Spring `RestClientException`, so a `catch` of that still catches it | `500` |

These codes say only what went wrong, such as "The other service did not answer in time". The body your caller
gets never names the other service, its address or its path. Your own log does: the code's cause, Spring's
`ResourceAccessException`, carries the address in the stack trace the web starter logs. Each code is also in the
`error.code` field of that log line and in the `error.code` tag of the request's `http.server.requests` timer.

A request that fails because another service answered with an error gets `500`, whatever that service answered,
unless you map that status to your own code and give your code a status
([below](#your-own-code-for-an-error-status)): your caller must not send the request again because of it. Stratum's clients never resend a `500`, while they resend
a `502`, `503`, `504` or `429` ([ADR 0030](../adr/0030-http-client-auth-resilience-and-connections.md), decision 2.2), so a `503` passed on
by each service in a chain would multiply the calls that reach the last one. Which code your caller gets, and what it
may do then, is in [What your caller learns](../guides/what-your-caller-learns.md).

The statuses are Stratum's defaults in the web starter's `stratum.web.error.statuses`. You change one the way you map
your own codes, and your value wins. Before you give `SX-HTTPC-0009` a status such as `503`, check that your callers
resend it only for requests that are safe to send twice:

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

In Java, set it in a `WebPropertiesCustomizer` ([Configure in Java](web.md#configure-in-java)). Start from the
statuses already set, so that the other defaults stay:

```java
var statuses = new HashMap<>(properties.error().statuses());
statuses.put("SX-HTTPC-0002", 502);
return properties.toBuilder().error(properties.error().toBuilder().statuses(statuses).build()).build();
```

Even in [backward-compatible mode](web-compatible-mode.md), a call that ends with a code is answered with Stratum's
error body and the status in the table above, as every failure with a Stratum code is. Error answers and answers that
cannot be read are answered with `500` in both modes.

What your service returns instead when a call fails, such as the last value it read, is your own business logic:
catch the failure, as [resilience: use it](resilience.md#use-it) shows. The resilience starter is **Planned**. Do not
add a second retry layer, such as Resilience4j's `@Retry`, around a client: the client already retries
([ADR 0030](../adr/0030-http-client-auth-resilience-and-connections.md), decision 2.1).

## `DownstreamException`

It extends Spring's `RestClientResponseException`, so `getStatusCode()`, `getResponseHeaders()`,
`getResponseBodyAsString()` and `getResponseBodyAs(Class)` work as on Spring's. It adds:

| Method | Returns |
| --- | --- |
| `getClient()` | The client's name, such as `inventory`. |
| `getEndpoint()` | The method and URI template, such as `GET /stock/{sku}`, or the method alone, such as `GET`, for a call given a whole `URI`; never a path or query value. |
| `getTraceId()` | The trace ID of the call, to find its logs; empty when none was being logged. |
| `getDownstreamCode()` | The `code` of an `application/problem+json` body, such as another Stratum service's `ORD-0001`; empty for any other body. |
| `getErrorCode()` | Your own code when the client's `errors` maps the status ([below](#your-own-code-for-an-error-status)); otherwise `SX-HTTPC-0009` for `429`, `502`, `503` or `504`, and `SX-HTTPC-0007` for any other status. |
| `getErrorDetail()` | Always empty, so that your caller's body never names the other service. |
| `getReport()` | The report Stratum answers and logs the failure with: the code, its message and the request's IDs. |

Its message is `inventory answered 404 to GET /stock/{sku}`. It never holds the body or a path or query value, so
logging it leaks no data. Uncaught, your caller gets its code and Stratum's body, never the other service's body,
with `500` unless you map the status to your own code and give that code a status.

A job, a message listener or a command-line application has no caller to answer. It can read the code to tell a
failure that may pass from one that will not: `SX-HTTPC-0009`, like `SX-HTTPC-0001` to `SX-HTTPC-0003`, may pass;
`SX-HTTPC-0007` will not ([Outside a web request](../guides/what-your-caller-learns.md#outside-a-web-request)). A
status you map to your own code carries that code instead, so a job that tests for `SX-HTTPC-0009` tests for your
code too when you map `429`, `502`, `503` or `504`.

## `UnreadableAnswerException`

The other service answered, but the client cannot read the answer's body as the method's type. It extends Spring's
`RestClientException`, so code that catches that keeps working, and its cause is Spring's own exception, which says
why. It adds `getClient()`, `getEndpoint()` and, with `SX-HTTPC-0008`, the same `getErrorCode()`, `getErrorDetail()`
and `getReport()` as `DownstreamException`. Its message is `inventory could not read the answer to GET
/stock/{sku}`. The other service may have done the work, so do not send a write again without checking first.

A request body that no converter can write, such as one with a content type no converter knows, is not this case:
the call fails with Spring's `RestClientException` before anything is sent.

A client you move to Stratum no longer throws Spring's subtypes, such as `HttpClientErrorException.NotFound`. For a
`404`, return `Optional` from the method ([below](#a-404-read-as-an-empty-optional)). For another status, catch
`DownstreamException` and check the status:

```java
try {
  return orders.reserve(order);
} catch (DownstreamException failure) {
  if (failure.getStatusCode().value() == 409) {
    return Reservation.alreadyTaken(order);
  }
  throw failure;
}
```

## A 404 read as an empty `Optional`

A method of a client's interface that returns `Optional` returns an empty one when the other service answers `404`.
That holds for each shape Spring wraps in `Optional`: `Optional<T>`, `Optional<ResponseEntity<T>>`,
`Optional<ResponseEntity<Void>>` and `Optional<HttpHeaders>`. `mapError` is not asked, and the `404` never counts for
the circuit breaker. Any other status still throws, and a method that does not return `Optional` still throws
`DownstreamException` for a `404`.

```java
@StratumClient("inventory")
public interface InventoryClient {

  @GetExchange("/stock/{sku}")
  Optional<Stock> stock(@PathVariable String sku);

  @GetExchange("/stock/{sku}")
  Optional<ResponseEntity<Stock>> stockWithTag(@PathVariable String sku);
}
```

```java
return inventory.stock(sku).orElseGet(() -> Stock.none(sku));
```

Only a client's interfaces do this. A call through the client's `RestClient` or `RestTemplate` bean, or
`stratumHttpClients.restClient("<name>")`, or through an `HttpServiceProxyFactory` you build on one, keeps Spring's
`HttpClientErrorException.NotFound`.

## Your own exception for an error answer

To turn one client's error answer into your own exception everywhere, throw it from `mapError` in a
`StratumClientCustomizer`; return to keep the `DownstreamException`. It is asked on each call that ends with an error
answer, in the customizers' order, so the first that throws wins. A call that ends with one of the codes
`SX-HTTPC-0001` to `SX-HTTPC-0006`, or with an answer that cannot be read, is never passed to it.

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

## Your own code for an error status

To give one status of the other service your own error code everywhere, without code, list it in the client's
`errors`. Only the statuses you list change; every other one keeps `SX-HTTPC-0007` or `SX-HTTPC-0009`. A call
answered with a listed status still throws a `DownstreamException`, with its status, headers and body, but its
`getErrorCode()` is your code, so a `catch` of `DownstreamException` keeps working, and a job or a listener reads your
code. Your caller gets your code with the status `stratum.web.error.statuses` gives it, or `500` when you give it
none. The message your caller gets is the same fixed text as for `SX-HTTPC-0007`, "The other service answered with
an error", whatever your own code's description says, and never the other service's body.

A status under `defaults` applies to every client, a client's own wins over it, and an operation's wins over its
client's. An operation is the methods marked `@StratumOperation` with its name:

```java
@StratumClient("inventory")
public interface InventoryClient {

  @StratumOperation("list-orders")
  @GetExchange("/orders")
  List<Order> listOrders();
}
```

```yaml
stratum:
  http-client:
    clients:
      inventory:
        errors:
          "409": ORD-0409
          "422": ORD-0422
        operations:
          list-orders:
            errors:
              "404": ORD-0404
  web:
    error:
      statuses:
        ORD-0409: 409
```

```properties
stratum.http-client.clients.inventory.errors[409]=ORD-0409
stratum.http-client.clients.inventory.errors[422]=ORD-0422
stratum.http-client.clients.inventory.operations.list-orders.errors[404]=ORD-0404
stratum.web.error.statuses.ORD-0409=409
```

In Java, set them on the client's settings in an `HttpClientPropertiesCustomizer`
([Configure in Java](http-client.md#configure-in-java)):

```java
var inventory =
    properties.clientSettings("inventory").toBuilder()
        .errors(Map.of(409, "ORD-0409", 422, "ORD-0422"))
        .operations(
            Map.of("list-orders", OperationSettings.builder().errors(Map.of(404, "ORD-0404")).build()))
        .build();
```

- **What it does not change.** The retries and the circuit breaker see the same answer as before, so a listed `503`
  is still sent again and counted. A customizer's `mapError` runs after the code is set, sees it in `getErrorCode()`,
  and may still throw your own exception. A `404` of a method that returns `Optional` is still an empty `Optional`.
- **Where it applies.** Calls of a client's interfaces only, not its `RestClient` or `RestTemplate` bean. A generated
  interface named in `interfaces` has no `@StratumOperation`, so it takes the client's and the defaults' codes.
- **What stops startup.** A status outside `400` to `599`, or a code that is empty, holds whitespace or starts with
  `SX-`, stops startup with `SX-CORE-1003`. An operation's name that is not lowercase letters, digits and hyphens, or
  that two methods of one client carry, stops it with `SX-HTTPC-1009`; an operation under `operations` that no method
  carries, or any `operations` under `defaults`, with `SX-HTTPC-1010`.

## The error body is read up to 64 KB

Of an error answer's body, at most 64 KB is read and kept, after gzip is undone, so a huge error body never fills the
memory. An answer that succeeds is read up to `response-body-limit` (below). The 64 KB limit has
no key. When the answer closes, the rest of the body is read and dropped up to 64 KB in all, so the connection can be
used again. A body that does not end within 64 KB, one that never ends, or one whose `Content-Length` is over 64 KB is
not read further: its connection is closed instead of being used again, so a huge error body never holds the call. A
body that stalls within those 64 KB is dropped, with its connection, when `total-timeout` runs out.

The 64 KB are counted after gzip is undone, so the memory a body takes stays bounded whatever it unzips to. Two cases
lose their connection without an error, and are accepted as they are:

- A gzip error body that unzips to 64 KB or more, even when few bytes were sent.
- A body of exactly 64 KB with no `Content-Length`, as when it is sent in chunks or with gzip: the drain would need one
  more read to see its end.

## The answer body is read up to 32 MB

Of a successful answer, status below 400, a client reads at most `response-body-limit`, 32 MB by default, after gzip
is undone ([ADR 0031](../adr/0031-http-client-errors-and-body-limits.md)). Set it per client or under `defaults`, from
`1B` to `1GB`, or `-1` for no limit ([properties](http-client.md#properties)).

- A read that passes the limit fails with `SX-HTTPC-0006`, status `500`, as does an answer whose `Content-Length` is
  over the limit, at once and before a byte is read. The connection is closed, not used again.
- The call is not retried, and the circuit breaker counts it as a success: the other service did answer.
- The caller's error names nothing internal. The logged cause names the client and the limit in bytes, never a byte of
  the body.
- A body the call does not read, such as for a method returning `void`, or reads only in part, is read and dropped
  when the answer closes, so the connection can be used again, but only up to the limit: a longer one is dropped with
  its connection, without an error. So is one of exactly the limit with no `Content-Length`, as the 64 KB error body
  above is.
- The limit counts bytes after gzip is undone. Bytes a service sends after the end of its gzip data are not counted;
  `total-timeout` ends them.
- A method returning `InputStream` or `ResponseEntity<InputStream>` streams, so it is not limited; a `Resource` is read
  whole, so it is.
- A client's service token is limited by the client's `response-body-limit` too, unless it is `-1`. A token answer
  over the limit is a token that cannot be fetched: the call ends with `SX-HTTPC-0002`, like any failed token fetch,
  not with `SX-HTTPC-0006`. Its logged cause ends with the `SX-HTTPC-0006` line, which names the client as
  `token:<name>`, such as `token:payments`, its token pool.
- The limit applies in both modes, `stratum.backward-compatible=true` included, and to a client's beans and mocks.

## Clients Stratum did not build

A call through the client's `RestClient` or `RestTemplate` bean, or `stratumHttpClients.restClient("<name>")`,
throws Spring's own exceptions, as a `RestClient` you built would, except for three Stratum codes: `SX-HTTPC-0003`
for a call refused by an open circuit breaker, `SX-HTTPC-0002` for a service token that cannot be fetched, and
`SX-HTTPC-0004` for a token-relay call with no verified caller (an answer over `response-body-limit` fails with
Spring's `RestClientException`, whose cause's message starts with `SX-HTTPC-0006`)
([ADR 0031](../adr/0031-http-client-errors-and-body-limits.md)). `mapError` and `errors` reach
interfaces only. The error body of such a call is still read up to 64 KB
([clients Stratum did not build](http-client-own-clients.md#what-differs-from-an-interface)).

## Startup errors

| Code | When |
| --- | --- |
| `SX-HTTPC-1001` | A `spring.http.serviceclient.<name>.*` key is set for a Stratum client. |
| `SX-HTTPC-1002` | A client method returns `Mono` or `Flux`. |
| `SX-HTTPC-1003` | A client name is not lowercase letters, digits and hyphens starting with a letter. |
| `SX-HTTPC-1004` | A client has no `url`. |
| `SX-HTTPC-1007` | A client's `interfaces` names a type that is not an interface on the classpath. |
| `SX-HTTPC-1008` | An interface marked `@StratumClient` for one client is named in another client's `interfaces`. |
| `SX-HTTPC-1009` | A `@StratumOperation` name is not lowercase letters, digits and hyphens, or two methods of one client carry it. |
| `SX-HTTPC-1010` | A client's `operations` names an operation no method of its interfaces carries, or `operations` is set under `defaults`. |

Each code, with its fix, is in [Error codes](../guides/error-codes.md#stratum-starter-http-client).
