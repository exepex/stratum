# Design note: A2, headers and interceptors for the blocking HTTP client starter (Feature track)

**Item:** ADR 0011 decision 1.3 (interceptors), 2.1 (`headers` and `copy-headers` merge), 2.4 (keys) and 3.4
(forbidden headers); gap review A9 (copy with nothing to copy), A14 (an interceptor sees no streamed body), A15
(merge). Lane A, after A1. Owner's go: 2026-11-09 13:26Z, "work autonomously as per the previous agreements to
finish HTTP client starter". Base: `main` at `4a3d942` plus A1a (PR #251); one pull request.

**Not in A2:** `trace-headers` (the trace context is the observability work's, ADR 8; lane B's call-log step owns
it unless the coordinator says otherwise); `afterReceive` (an ADR "to file" issue); the reactive starter. That an
interceptor runs on *every attempt* needs lane B's retry: the test sits with whichever of B1 or A2 merges second
(B1 note: `RetryIntegrationTest.interceptorRunsOnEveryAttempt`).

## 1. Who uses it, and does anything change for them?
New, existing and hybrid services, with Stratum as parent or next to a company framework. A client without
`headers`, `copy-headers` or interceptors is unchanged: no default header, no factory wrapper
(`HeadersIntegrationTest.noneSet`). API (library, no Spring HTTP types): `ClientInterceptor`,
`GlobalClientInterceptor`, `OutgoingRequest`, `@StratumClient(interceptors = ...)`. One key line, or one bean.

## 2. How is it built, and why this way?
Static headers use `RestClient.Builder.defaultHeaders` (Spring), and copied headers an initializer, as A1 does: no
body buffering. Interceptors wrap the client's request factory, never a `ClientHttpRequestInterceptor` (it buffers
every body, lane C). Rejected: Spring's interceptor. New types in the existing packages; the library gains no
dependency and no package depends on a new one, so no cycle.

## 3. Is it safe and fast?
Fixed forbidden names stop startup; copied values come only from the current request, on the caller's thread. With
no interceptor, nothing is wrapped; with one, the body is copied only if an interceptor asks for it, and a streamed
body is never read. No thread hop. Hostile inputs and the eight parameters below.

## 4. Which rules and owner decisions does it follow?
ADR 0011 1.3, 2.1, 2.4, 3.4, 7; gap review A9, A14, A15; ST-ENG-05, -18, -24, -32, -34, -42, -51, -53, -56, -57;
ST-SEC-02, -06; ST-DOC-04, -05, -09; ST-TEST-01, -15, -17, -20, -31. Library facts with sources below.

## Details

### Keys
Under `defaults` and under `clients.<name>`. Both bind from files and from `HttpClientPropertiesCustomizer`
(fields of the `ClientSettings` record), with IDE metadata from the record's Javadoc.

| Key | Default | A real case that changes it |
|---|---|---|
| `headers` (map, name to value) | none | Every call to a partner carries `X-Partner-Id: orders` |
| `copy-headers` (list of names) | none | The gateway's `X-Tenant` goes on to the downstream |

- **Merge (ADR 2.1, A15).** A client's `headers` are added to `defaults.headers`; for the same name, compared
  without case, the client's value wins; an empty value drops the default. The `copy-headers` lists are joined,
  without duplicates, again without case.
- **Order on the wire.** A header the method sets with `@RequestHeader` wins over a configured one (Spring adds
  default headers first and the request's own after them). A copied header is set after both, but only when the
  request has none of that name.
- **Forbidden names (ADR 3.4), `SX-CORE-1003`, rule `headers-allowed`.** `Authorization`, `Cookie`, `Set-Cookie`,
  `Proxy-Authorization`, and the client's API-key header when `auth.api-key` is set, are refused in `headers` or
  `copy-headers`, under `defaults` or a client, without case. The check reads each client's merged settings (the
  A1 lesson). The message names the client, the key and the header name; never a value.
- **Map keys.** Spring Boot binds a map key with a dash in `.properties` and YAML; to keep the case of a name, the
  docs show the bracket form (`headers.[X-Partner-Id]`). Header names are case-insensitive on the wire, so a lowered
  name still works. Not verified yet: the binding test proves both forms in the build.

### Copied headers
- Read from `RequestContextHolder` on the caller's thread when Spring creates the request. Present only inside a
  servlet request; on a scheduled job or an `@Async` thread there is nothing to copy, and nothing is sent (A9).
- Servlet classes are looked up only when a client has `copy-headers`. A service without the servlet API sends
  nothing, the same as a job: a company's shared `defaults.copy-headers` must not stop its non-web services. Not
  verified: whether Boot's `RequestContextFilter` is on in every servlet app; the test uses a real MVC request.
- All values of a name are copied. Their size is bounded by the server's own header limit.

### Interceptors (ADR 1.3)
```java
public interface ClientInterceptor {
  void beforeSend(OutgoingRequest request);
}
public interface GlobalClientInterceptor extends ClientInterceptor {}

public interface OutgoingRequest {        // implemented by the starter, @StratumInternal
  String client();                        // the client's name
  String method();
  URI uri();
  Optional<String> header(String name);
  void setHeader(String name, String value);
  Optional<byte[]> body();                // empty with no body, or a streamed one (ADR 7, A14)
}
```
- `@StratumClient(value = "orders", interceptors = OrderSigner.class)` names beans by type. A client's interceptors
  are those named by its interfaces, joined: one client is one `RestClient`, which also serves
  `restClient("orders")`. A named class with no bean, or two, stops startup with Spring's own bean error.
- Every `GlobalClientInterceptor` bean runs on every client, before the client's own, in `@Order` order.
- **How:** `InterceptingRequestFactory` (`@StratumInternal`) wraps the client's request factory in
  `StratumClientBuilders.configure`, only when the client has at least one interceptor. Its request is a
  `StreamingHttpOutputMessage`: it keeps the `Body` Spring's converter gives to `setBody`, and in `execute()` runs
  the interceptors, then hands the body on and executes the real request.
  - `body()` writes a repeatable `Body` to bytes once, on first call, and the same bytes are then sent. A
    non-repeatable `Body` (a streamed upload) is never read: `body()` is empty and the upload still streams.
  - Spring creates a new request for each attempt and runs initializers at creation, so interceptors run after
    auth and once per attempt.
- An interceptor's exception fails the call before anything is sent; lane B's rules decide whether it is retried
  (it is not transient, so not).
- `OutgoingRequest.toString` shows the client, method and path only (ST-SEC-02: headers may hold the credential).
- Lane C's mocks swap the request factory; the interceptor wrapper goes on last, so a mocked call also runs the
  team's interceptors. To agree with lane C before the code.

### Library facts (spring-web 7.0.10, read with `javap`)
- `AbstractHttpMessageConverter.write` and `AbstractSmartHttpMessageConverter.write` call `setBody` when the target
  is a `StreamingHttpOutputMessage`, with `repeatable()` from `supportsRepeatableWrites`.
- `supportsRepeatableWrites` is true for the Jackson, `byte[]` and `String` converters, false by default (resources,
  input streams).
- `AbstractStreamingClientHttpRequest.setBody` is public; `getBody()` buffers.
- Not verified: whether `FormHttpMessageConverter` uses `setBody`. If it writes through `getBody()`, a form body
  goes straight to the real request and `body()` is empty; the build tests it and the docs say which.

### The eight parameters (ST-AG-14)
- **Speed and throughput:** no wrapper and no extra work for a client without interceptors; static headers are set
  once on the builder. With interceptors: a list walk per attempt.
- **Memory and leaks:** a body copy only when an interceptor reads it, held for that request only.
- **Log volume:** nothing new is logged.
- **Data leaks and security:** forbidden names keep users' identities out of copied headers; values never appear
  in errors or `toString`; copying a header a caller controls (such as `X-Forwarded-For`) is the team's choice,
  and the docs say so.
- **Maintainability and scalability:** two small starter classes and one record change; the API holds no Spring
  type, so the reactive starter can share it.

### Tests (planned)
| What | Test |
|---|---|
| Merge: client wins without case, empty value drops, lists joined | `HttpClientPropertiesTest$Headers` |
| Each forbidden name, in each key, under `defaults` and a client, and the API-key header | `HttpClientPropertiesTest$InvalidSettings`, data in a `@FieldSource` constant |
| Properties, YAML and Java binding, bracket and plain names | `HttpClientPropertiesTest` |
| Static headers on interface and ad-hoc calls; `@RequestHeader` wins; other clients get none | `HeadersIntegrationTest` |
| Copied from a real MVC request; nothing outside a request | `CopyHeadersIntegrationTest` |
| Global and client interceptors, order, after auth, client scope, ad-hoc calls | `InterceptorIntegrationTest` |
| `body()` holds the JSON sent; a streamed upload arrives chunked and `body()` is empty | `InterceptorIntegrationTest$Body`, with lane C's streaming server |
| An interceptor's exception: nothing sent | `InterceptorIntegrationTest` |
| No interceptor: no wrapper | `StratumClientBuildersTest` |

### Docs
`docs/modules/http-client.md` (key table, merge rules, planned list), a new `docs/modules/http-client-headers.md`
(both formats, Java, interceptors, forbidden names), `docs/configuration/reference.md`, `docs/README.md`.
