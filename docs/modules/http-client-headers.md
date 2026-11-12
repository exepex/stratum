# HTTP client: headers and interceptors

Fixed headers, headers copied from the request your service is answering, and interceptors: your own code that
changes each call before it is sent, such as to sign it. Part of [`stratum-starter-http-client`](http-client.md).

[← HTTP client](http-client.md) · [Documentation home](../README.md) ·
Design: [ADR 0011](../adr/0011-http-client-starters.md), decisions 1.3, 2.1 and 3.4

## Fixed headers

`headers` sends the same headers on every call of a client: through its interfaces, `restClient(name)` and its
`RestClient` and `RestTemplate` beans. Under `defaults`, they go on every client's calls:

```yaml
stratum:
  http-client:
    defaults:
      headers:
        "[X-Origin]": shop
    clients:
      partner:
        url: https://partner.example
        headers:
          "[X-Partner-Id]": orders
```

```properties
stratum.http-client.defaults.headers.[X-Origin]=shop
stratum.http-client.clients.partner.url=https://partner.example
stratum.http-client.clients.partner.headers.[X-Partner-Id]=orders
```

Write each header name in brackets, as above, so that Spring Boot keeps it exactly as written. Header names are not
case-sensitive on the wire, so a name written without brackets still works.

How a client's `headers` merge with `defaults.headers`:

- they are added to the defaults' headers;
- for the same name, compared without case, the client's value wins;
- an empty value, such as `"[X-Origin]": ""`, drops the default for that client and sends nothing. It stays in
  the client's merged settings, so it keeps dropping the default when a Java customizer stores those settings back,
  and `stratumHttpClients.settings(name)` shows it with its empty value.

## Copied headers

`copy-headers` copies headers from the request your service is answering onto each call it makes while answering
it. A gateway's `X-Tenant` reaches the next service this way:

```yaml
stratum:
  http-client:
    defaults:
      copy-headers: [X-Tenant]
    clients:
      partner:
        url: https://partner.example
        copy-headers: [X-Region]
```

```properties
stratum.http-client.defaults.copy-headers=X-Tenant
stratum.http-client.clients.partner.url=https://partner.example
stratum.http-client.clients.partner.copy-headers=X-Region
```

A client's list is joined with the defaults' list, each name once, compared without case, so `partner` copies both
`X-Tenant` and `X-Region`. Every value of a name is copied, as separate header lines, in the order they came. A
name the request does not have, or has with an empty value, sends nothing.

The headers are read on the thread that makes the call, from the request Spring MVC is handling there. Nothing is
copied, and the call still works, when there is no such request:

- in a scheduled job, a message listener or a service with no web endpoints;
- on another thread, such as an `@Async` method or a controller that finishes in a `CompletableFuture`;
- in a WebFlux service, or one without the servlet API.

Copy only headers you mean to pass on. A header the caller sets, such as `X-Forwarded-For`, reaches the other service
as the caller sent it: copying it is your choice. `defaults.copy-headers` also applies to the clients Stratum did not
build that use `stratumHttpClients.restClient(name)`.

### One value per name

When the same header comes from several places, the call sends one value, in this order:

1. the method's own header, from `@RequestHeader` or `.header(...)` on an ad-hoc call;
2. `headers`;
3. the copied header.

## Refused headers

These headers carry a user's identity or a credential, so they are never accepted in `headers` or `copy-headers`,
under `defaults` or a client, in any case:

- `Authorization`;
- `Cookie`;
- `Set-Cookie`;
- `Proxy-Authorization`;
- the client's API key header, `auth.api-key-header` (`X-Api-Key` by default), when it sets `auth.api-key`.

One of them stops startup with
`SX-CORE-1003: Invalid settings: stratum.http-client.headers-allowed breaks @AssertTrue`. The message names the
rule only, never the client, the header or its value, since any of them could be a secret. Look for the names above
in your settings. Send a credential with the [`auth` settings](http-client-auth.md) instead.

Neither `headers`, copied headers nor interceptors reach the identity provider when a client fetches a service token
(`auth.client-credentials`).

## Configure in Java

`headers` and `copy-headers` are fields of `ClientSettings`, so an `HttpClientPropertiesCustomizer` sets them, and
Java wins over the files:

```java
public class PartnerHeaderSettings implements HttpClientPropertiesCustomizer {

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var partner =
        properties.clientSettings("partner").toBuilder()
            .headers(Map.of("X-Partner-Id", "orders"))
            .copyHeaders(List.of("X-Region"))
            .build();
    var clients = new HashMap<>(properties.clients());
    clients.put("partner", partner);
    return properties.toBuilder().clients(clients).build();
  }
}
```

List the class in `META-INF/spring.factories`, as [Configure in Java](http-client.md#configure-in-java) shows.

## Interceptors

An interceptor is a bean that changes each call of a client before it is sent. A `ClientInterceptor` runs for the
clients that name it; a `GlobalClientInterceptor` runs for every client:

```java
@Component
class OrderSigner implements ClientInterceptor {

  @Override
  public void beforeSend(OutgoingRequest request) {
    var body = request.body().orElse(new byte[0]);
    request.setHeader("X-Signature", signatureOf(body));
  }
}

@StratumClient(value = "partner", interceptors = OrderSigner.class)
public interface PartnerClient {

  @PostExchange("/orders")
  Order create(@RequestBody Order order);
}
```

`OutgoingRequest` gives the client's name, the method, the full address, the headers, `setHeader` and the body.

- **Order:** every `GlobalClientInterceptor`, in `@Order` order, then the client's own, in the order they are named.
- **Every attempt, after the credential:** each attempt of a call, a retry or a resend with a new token included,
  runs the interceptors again, after the client's `auth` header is set, so an interceptor sees it.
- **A failure:** an exception an interceptor throws fails the call before anything is sent, and reaches your code as
  the interceptor threw it, type unchanged: a `503` the interceptor got from another service stays that exception,
  and a `404` on an `Optional` method is thrown, not read as empty. The call is neither retried, nor sent again with
  a new token, nor recorded by the circuit breaker. An `Error` is not caught.
- **Where the failure is wrapped:** until the call's outermost layer throws it to your code, the failure travels in an
  internal Stratum wrapper, `InterceptorFailure`, whose cause is the exception the interceptor threw. Three things see
  the wrapper, not your exception:
  - a rule your `customizeRetry` gives to `retryOnException`; give rules to `retryOnResult` instead
    ([resilience](http-client-resilience.md#rules-the-keys-cannot-express));
  - an `ObservationHandler`, so Spring Boot's `http.client.requests` timer is tagged `exception=InterceptorFailure`;
  - a decorator of the client's interfaces you add with Spring's `RestClientHttpServiceGroupConfigurer`, when that
    configurer is ordered before Stratum's.
- **Named and global:** a bean that is a `GlobalClientInterceptor` and is also named in `interceptors` runs once, as
  a global one. A `ClientInterceptor` that no client names never runs.
- **Missing bean:** each type named in `interceptors` needs exactly one bean; with none, or two, startup stops with
  Spring's own bean error.
- **An interceptor that uses a client:** the interceptor beans are created on a client's first call, not while the
  clients are built, so an interceptor may inject a Stratum client, such as one that fetches its signing key.
- **No `@StratumClient`:** a generated interface named in `interfaces`, or a client used only through
  `stratumHttpClients.restClient(name)`, cannot name interceptors. A `GlobalClientInterceptor` that checks
  `request.client()` reaches it:

```java
@Component
class PartnerOnly implements GlobalClientInterceptor {

  @Override
  public void beforeSend(OutgoingRequest request) {
    if ("partner".equals(request.client())) {
      request.setHeader("X-Channel", "web");
    }
  }
}
```

Interceptors run inside Stratum's request factory, not as Spring's `ClientHttpRequestInterceptor`, which would make
Spring hold every request body in memory ([ADR 0012](../adr/0012-http-client-changes-found-while-building.md)). A
`StratumClientCustomizer` that replaces a client's request factory replaces the pool, the copied headers, the
interceptors and the [trace headers](http-client-observability.md#trace-headers) too.

### Bodies

`request.body()` returns the body only for a method's `@RequestBody` that is not streamed, such as an object written
as JSON. It is read once and then sent as read. It is empty for:

- a streamed body: a file or any other `Resource`, an `InputStream`, a multipart body or a
  `StreamingHttpOutputMessage.Body`, which is never read, so an upload still streams;
- a call through `stratumHttpClients.restClient(name)`, where your code holds the body already;
- a body a message converter writes straight to the request's stream.

#### The limit on a body an interceptor reads

`request.body()` reads at most `interceptor-body-limit` of a body: 1 MB by default, from 1 byte to 1 GB, under
`defaults` or for one client. `0` is refused at startup, unlike the web module's `stratum.web.body-limit`, where it
means no limit. The limit also holds while `stratum.backward-compatible` is `true`.

```yaml
stratum:
  http-client:
    clients:
      webhooks:
        url: https://hooks.example
        interceptor-body-limit: 8MB
```

```properties
stratum.http-client.clients.webhooks.url=https://hooks.example
stratum.http-client.clients.webhooks.interceptor-body-limit=8MB
```

In Java, set `interceptorBodyLimit(DataSize.ofMegabytes(8))` on the client's `ClientSettings` builder in an
`HttpClientPropertiesCustomizer`, as shown above.

- **Above the limit**, the call fails with `SX-HTTPC-0005` (status 500) and nothing is sent. Reading stops at the
  limit, so a larger body is never held whole. The call is not retried, the circuit breaker records it neither as
  a success nor as a failure, and the caller sees only the code. The logged cause names the client and the limit in
  bytes, such as `client webhooks, interceptor-body-limit 8388608B`, and has no cause of its own, so it
  holds no byte of the body.
- **Memory:** a call whose interceptor reads its body holds up to about three times the limit while it runs: up to
  twice while the copy grows and is taken out once, plus one copy for each `body()` call. Calls running at the same
  time add up.
- **A large body to sign:** stream it, such as a `Resource` or a `StreamingHttpOutputMessage.Body`, and sign a digest
  of it that your code computes, instead of raising the limit.

The text of an `OutgoingRequest`, as a log line would show it, names the client, the method and the path only, never
a header, the query or the body.
