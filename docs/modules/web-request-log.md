# Web: request log

**Status: Built**, in `stratum-starter-webmvc` and `stratum-starter-webflux`, unless
`stratum.web.enabled=false` or `stratum.web.request-log.enabled=false`.

[← Web](web.md) · [Documentation home](../README.md) · Property prefix: `stratum.web.request-log`

## What it does
Each request writes one line at `INFO`, through the logger `com.exepex.stratum.web.request-log`, once
it is answered:

```text
GET /orders/{orderId} answered 200 in 12 ms
```

The line carries the request fields and the IDs described above, plus `httpStatus` and
`durationMs`. With the [logging starter](../../starters/stratum-starter-logging/README.md) the line
is JSON, so each is a field you can search and chart.

- Spring MVC writes the line once the response is written, also for a `Callable` or
  `DeferredResult` that answers later. A request whose error no handler answered is logged with
  `500`.
- WebFlux writes it when the response starts, so it has the status the error handler set. For a
  streamed response, `durationMs` is the time until the first bytes were sent. A request the caller
  cancels before then is logged with `499`, the status nginx uses for a closed request.
- Paths under `/actuator/**` and the static files Spring serves write no line. A path that matches
  nothing is answered `404` and logged.
- `skip-paths` replaces the default list, so keep `/actuator/**` in it when you add your own paths.
  Each is a Spring path pattern, such as `/internal/**`.
- To stop the line, set `stratum.web.request-log.enabled=false`, or set the logger above to `WARN`.

## Headers in the line

`request-headers` and `response-headers` map a header to the field it is written as. A field name
starts with a letter and has up to 64 letters, digits, `_` and `.`. Header names match in any case.
Each value is made safe for the logs, as a request field is, and several values are joined with
`, `. `Authorization`, `Cookie`, `Set-Cookie` and `X-Api-Key` are never written, even when named,
and neither is any header in `denied-headers`.

## Token claims

With `token-payload=true`, the line carries some claims of the caller's `Authorization: Bearer`
JWT, decoded, as `jwtClaims`, such as `{"sub":"checkout-service","scope":"orders.read"}`. Only the
claims listed in `token-claims` are written: by default `sub`, `azp`, `client_id` and `scope`, which
say who called, through which client and with what rights. Every other claim, such as `email` or
`name`, is left out, and so are the token's header and signature. A token that has none of the
listed claims, an encrypted token, or one that is not a JWT, writes nothing.

Stratum does not verify the token here, so the claims are what the caller says, not who the caller
is. They are off by default. A list you set replaces the default, so list only claims that hold no
personal data.

## Configure it

In `application.properties`:

```properties
stratum.web.request-log.skip-paths=/actuator/**,/internal/**
stratum.web.request-log.request-headers[X-Tenant-Id]=tenantId
stratum.web.request-log.response-headers[X-Cache]=cacheResult
stratum.web.request-log.denied-headers=X-Internal-Key
stratum.web.request-log.token-payload=true
stratum.web.request-log.token-claims=sub,azp,tenant
```

or in `application.yml`, where the brackets need quotes:

```yaml
stratum:
  web:
    request-log:
      skip-paths:
        - /actuator/**
        - /internal/**
      request-headers:
        "[X-Tenant-Id]": tenantId
      response-headers:
        "[X-Cache]": cacheResult
      denied-headers:
        - X-Internal-Key
      token-payload: true
      token-claims:
        - sub
        - azp
        - tenant
```

A service whose gateway already logs every request can stop the line. In `application.properties`:

```properties
stratum.web.request-log.enabled=false
```

or in `application.yml`:

```yaml
stratum:
  web:
    request-log:
      enabled: false
```

The [property table](web.md#properties) lists every key with its type and default.

## Configure in Java

The same settings, through the `WebPropertiesCustomizer` that [Web](web.md#configure-in-java) describes. This
class sets the headers and claims of the first example:

```java
public class OrdersRequestLogSettings implements WebPropertiesCustomizer {

  @Override
  public WebProperties customize(WebProperties properties) {
    return properties.toBuilder()
        .requestLog(
            properties.requestLog().toBuilder()
                .skipPaths(List.of("/actuator/**", "/internal/**"))
                .requestHeaders(Map.of("X-Tenant-Id", "tenantId"))
                .responseHeaders(Map.of("X-Cache", "cacheResult"))
                .deniedHeaders(List.of("X-Internal-Key"))
                .tokenPayload(true)
                .tokenClaims(List.of("sub", "azp", "tenant"))
                .build())
        .build();
  }
}
```

To stop the line, as in the second example, replace the settings above with `.enabled(false)`:

```java
.requestLog(properties.requestLog().toBuilder().enabled(false).build())
```

A Java value wins over the same key in a file.
