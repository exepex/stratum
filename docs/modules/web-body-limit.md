# Web: request body size limit

**Status: Built**, in `stratum-starter-webmvc` and `stratum-starter-webflux`, when
`stratum.web.enabled=true`.

[← Web](web.md) · [Documentation home](../README.md) · Property prefix: `stratum.web.body-limit`

## What it does

With `stratum.web.backward-compatible=false`, every request body has a size limit, 256KB unless you
set another. While `stratum.web.backward-compatible` is `true`, the default, there is no limit
unless you set one; see
[Backward-compatible mode](web-compatible-mode.md#time-and-body-size-limits). Once a limit applies,
a request whose body is larger is answered with `413 Content Too Large` and the code `SX-WEB-1008`,
the service logs one WARN line with the limit, and a caller can no longer fill the service's memory
with one huge request.

Stratum checks the body twice:

- **Before it is read.** A request whose `Content-Length` header is over the limit is refused
  before your handler reads any of it.
- **While it is read.** A body sent in chunks, with no `Content-Length`, is counted as it arrives
  and stopped once it passes the limit, before the rest of it is held in memory.

## Set the limits

In `application.properties`:

```properties
stratum.web.body-limit.default-limit=512KB
stratum.web.body-limit.routes[/uploads/**]=20MB
stratum.web.body-limit.routes[/internal/imports/*]=0
```

or in `application.yml`:

```yaml
stratum:
  web:
    body-limit:
      default-limit: 512KB
      routes:
        "[/uploads/**]": 20MB
        "[/internal/imports/*]": 0
```

- A limit is a size, such as `256KB`, `5MB` or `20MB`. `0` means no limit.
- `routes` maps a Spring path pattern to its limit. Write each pattern in square brackets, as above,
  so Spring Boot keeps its `/` and `*`. When several patterns match a path, the most specific wins.

Each key, with its type and default, is listed in [Web → Properties](web.md#properties).

## Give one API its own limit

`@RequestBodyLimit` on a controller method wins over the routes and the default, whether it lets
the API accept more or less:

```java
@RequestBodyLimit(20)
@PostMapping("/documents")
DocumentId upload(InputStream body) {
  return documents.store(body);
}

@RequestBodyLimit(value = 16, unit = DataUnit.KILOBYTES)
@PostMapping("/prices")
void updatePrice(@RequestBody PriceChange change) {
  prices.update(change);
}
```

The unit is megabytes unless you set another. `@RequestBodyLimit(0)` means no limit. The
annotation works on `@RequestMapping` methods. A WebFlux router function has no method to annotate,
so it uses the routes and the default. On Spring MVC, Spring's form filter reads a `PUT`, `PATCH` or
`DELETE` form before the method is known, so a larger limit for such a form is set with a route.

So the limit of a request is, in order: its method's `@RequestBodyLimit`, then the most specific
route that matches its path, then the default.

## Uploads of 5 to 20 MB

An upload API usually needs a larger body limit and more time, because it answers only once it has
read the whole body. Give it both:

```java
@RequestBodyLimit(20)
@ResponseTimeout(0)
@PostMapping("/documents")
DocumentId upload(InputStream body) {
  return documents.store(body);
}
```

or by path:

```properties
stratum.web.body-limit.routes[/uploads/**]=20MB
stratum.web.timeout.routes[/uploads/**]=0
```

```yaml
stratum:
  web:
    body-limit:
      routes:
        "[/uploads/**]": 20MB
    timeout:
      routes:
        "[/uploads/**]": 0
```

Read the body as a stream, such as an `InputStream` or a `Flux<DataBuffer>`, and write it on as it
arrives. Reading 20MB into a `byte[]` or a `String` holds all of it in memory for each upload at
once.

## Spring's own limits

Spring Boot has limits of its own for some kinds of body. They apply as well as Stratum's, so an
upload API that accepts more than they allow raises both.

| Stack | Body | Spring Boot property | Default |
| --- | --- | --- | --- |
| Spring MVC | Each file of a multipart upload | `spring.servlet.multipart.max-file-size` | `1MB` |
| Spring MVC | A whole multipart upload | `spring.servlet.multipart.max-request-size` | `10MB` |
| Spring MVC on Tomcat | A form, `application/x-www-form-urlencoded` | `server.tomcat.max-http-form-post-size` | `2MB` |
| WebFlux | A body decoded in memory, such as JSON or a `String` | `spring.http.codecs.max-in-memory-size` | `256KB` |
| WebFlux | Each part of a multipart upload kept in memory | `spring.webflux.multipart.max-in-memory-size` | `256KB` |
| WebFlux | Each part of a multipart upload written to disk | `spring.webflux.multipart.max-disk-usage-per-part` | `10MB`, set by Stratum in standard mode |
| WebFlux | The number of parts of a multipart upload | `spring.webflux.multipart.max-parts` | `50`, set by Stratum in standard mode |

A WebFlux part larger than `max-in-memory-size` is written to disk. Spring Boot sets no limit on that
disk use or on the number of parts, and a multipart upload sent in chunks is not limited by Stratum's
own limit (see [What is counted](#what-is-counted)), so one request could fill the disk. With
`stratum.web.enabled=true` and `stratum.web.backward-compatible=false`, the WebFlux starter
therefore gives each part `10MB` of disk and an upload `50` parts. In backward-compatible mode it
sets neither, so an upload that worked before Stratum keeps working. Your own value for either key wins, from a file, an environment variable or
Java. A part over its disk limit is answered with `413` and `SX-WEB-1008`, as a body over Stratum's
limit is. An upload with more parts than allowed is answered with `400` and `SX-WEB-1002`, because
Spring reports it as an upload it cannot read.

For example, a Spring MVC service that takes files of up to 20MB as multipart uploads sets the
file limit to 20MB, and the upload's limits a little higher, because an upload holds the file's
name, headers and boundaries as well as the file:

```properties
stratum.web.body-limit.routes[/uploads/**]=21MB
spring.servlet.multipart.max-file-size=20MB
spring.servlet.multipart.max-request-size=21MB
```

```yaml
stratum:
  web:
    body-limit:
      routes:
        "[/uploads/**]": 21MB
spring:
  servlet:
    multipart:
      max-file-size: 20MB
      max-request-size: 21MB
```

A WebFlux service that takes the same uploads, one file each, raises the 10MB disk limit per part:

```properties
stratum.web.body-limit.routes[/uploads/**]=21MB
spring.webflux.multipart.max-disk-usage-per-part=20MB
spring.webflux.multipart.max-parts=10
```

```yaml
stratum:
  web:
    body-limit:
      routes:
        "[/uploads/**]": 21MB
spring:
  webflux:
    multipart:
      max-disk-usage-per-part: 20MB
      max-parts: 10
```

## What is counted

Stratum counts the body your handler reads: through `@RequestBody`, an `InputStream` or a `Reader`
on Spring MVC, and through `@RequestBody`, including a `Flux<DataBuffer>`, on WebFlux.

Spring reads forms and multipart uploads itself, such as for `@RequestParam`, `@RequestPart` or
`@ModelAttribute`, so those are checked by their `Content-Length` only. On Spring MVC the server
reads a multipart upload, under Spring's limits above, before Stratum can refuse it. A form or
multipart upload sent in chunks, with no `Content-Length`, is limited by Spring's limits only. The
exception is a `PUT`, `PATCH` or `DELETE` form on Spring MVC: Spring's form filter reads it through Stratum's
counter, so it is counted as it arrives. On WebFlux in standard mode, a multipart upload is limited
by Stratum's defaults for `spring.webflux.multipart.max-disk-usage-per-part` and `max-parts`, shown
above.

## Configure in Java

As every setting, the limits can be set in a `WebPropertiesCustomizer`, and Java wins over the files:

```java
public class OrdersWebSettings implements WebPropertiesCustomizer {

  @Override
  public WebProperties customize(WebProperties properties) {
    return properties.toBuilder()
        .bodyLimit(
            properties.bodyLimit().toBuilder()
                .defaultLimit(DataSize.ofKilobytes(512))
                .routes(Map.of("/uploads/**", DataSize.ofMegabytes(20)))
                .build())
        .build();
  }
}
```

List the class in `META-INF/spring.factories`, as the
[web MVC starter's README](../../starters/stratum-starter-webmvc/README.md#configure-in-java) shows.

## What the caller and the logs see

The caller gets the [error body](../../starters/stratum-starter-webmvc/README.md#the-error-body) every
error gets, with the trace ID to quote to support:

```json
{
  "type": "about:blank",
  "title": "Content Too Large",
  "status": 413,
  "detail": "The request body is larger than this API accepts",
  "code": "SX-WEB-1008",
  "severity": "ERROR",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "correlationId": "checkout-777"
}
```

The service logs one WARN line, `The request body is larger than its limit of 262144 bytes`, with
the request fields, and the [request log](web.md#request-log) line has the status.
