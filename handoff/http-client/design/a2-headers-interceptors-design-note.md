# Design note: A2, headers and interceptors for the blocking HTTP client starter (Feature track), round 2

**Item:** ADR 0011 decision 1.3 (interceptors), 2.1 (`headers` and `copy-headers` merge), 2.4 (keys) and 3.4
(forbidden headers); gap review A9 (copy with nothing to copy), A14 (an interceptor sees no streamed body), A15
(merge). Lane A, after A1. Owner's go: 2026-11-09 13:26Z, "work autonomously as per the previous agreements to
finish HTTP client starter". Base: `main` at `4a3d942` plus A1a (PR #251) and lane B's B1 (`StreamedBody`, the
per-group proxy-factory hook); the code starts once B1 has merged. One pull request.

**Not in A2:** `trace-headers` (lane B's B3, coordinator 15:26Z); `afterReceive` (an ADR "to file" issue); the
reactive starter. "An interceptor runs on every attempt" is tested by B1
(`RetryIntegrationTest.interceptorRunsOnEveryAttempt`), so A2 adds no copy.

Round 1 (Opus, FAIL, `reviews/judge/A2-headers-note.md`): all seven findings accepted; see "Changes from round 1".

## 1. Who uses it, and does anything change for them?
New, existing and hybrid services, with Stratum as parent or next to a company framework. A client without
`headers`, `copy-headers` or interceptors is unchanged: no default header, no wrapper (`StratumClientBuildersTest`).
API, all in `com.exepex.stratum.httpclient`: `ClientInterceptor`, `GlobalClientInterceptor`, `OutgoingRequest`, and
`@StratumClient(interceptors = ...)`. One key line, or one bean.

## 2. How is it built, and why this way?
Static headers use Spring's `RestClient.Builder.defaultHeaders`. Copied headers and interceptors both run in one
request-factory wrapper at `execute()`, after Spring has set the request's own headers. Rejected: Spring's
`ClientHttpRequestInterceptor`, which buffers every body (lane C). API in the library, wrapper in the starter's
existing package; the starter already depends on the library, so no cycle.

## 3. Is it safe and fast?
Fixed forbidden names stop startup; copied values come only from the current request, on the caller's thread.
No wrapper without copy or interceptor; a body is copied only when an interceptor asks for it and B1's
`StreamedBody` says it is not streamed. No thread hop. Hostile inputs and the eight parameters below.

## 4. Which rules and owner decisions does it follow?
ADR 0011 1.3, 2.1, 2.4, 3.4, 7; ADR 0012 (no buffering interceptor); gap review A9, A14, A15; coordinator
15:26Z (B3, B1, C3 attributes); ST-ENG-05, -18, -24, -34, -42, -53, -56, -57; ST-SEC-01, -02, -06; ST-DOC-04, -05,
-09; ST-TEST-01, -15, -17, -20, -31. Library facts with sources below.

## Details

### Where each part lives
| Type | Module, package | Label |
|---|---|---|
| `ClientInterceptor`, `GlobalClientInterceptor`, `OutgoingRequest`, `StratumClient.interceptors()` | `libs/stratum-http-client`, `com.exepex.stratum.httpclient` | API (no Spring HTTP type in them) |
| `headers`, `copy-headers` in `ClientSettings`; merge in `ClientSettings.orElse`; forbidden-name rule in `HttpClientProperties` | same | API record, existing |
| `OutgoingStepsRequestFactory` (wrapper) and its request, `CopiedHeaders` (copy step), `DefaultOutgoingRequest` | `starters/stratum-starter-http-client`, `com.exepex.stratum.restclient` | `@StratumInternal` |
| `ClientInterceptors` (each client's interceptor classes, from its annotated interfaces) | same | `@StratumInternal` |
| `StreamedBodyMarker` (`HttpRequestValues.Processor`) | same | `@StratumInternal` |

### Keys
Under `defaults` and under `clients.<name>`. Both bind from files and from `HttpClientPropertiesCustomizer`
(fields of the `ClientSettings` record), with IDE metadata from the record's Javadoc.

| Key | Default | A real case that changes it |
|---|---|---|
| `headers` (map, name to value) | none | Every call to a partner carries `X-Partner-Id: orders` |
| `copy-headers` (list of names) | none | The gateway's `X-Tenant` goes on to the downstream |

- **Merge (ADR 2.1, A15).** A client's `headers` are added to `defaults.headers`; for the same name, compared
  without case, the client's value wins; an empty value drops the default. The `copy-headers` lists are joined,
  without duplicates, without case.
- **One value per name on the wire.** Precedence: the method's own (`@RequestHeader`, or `.header(...)` on an
  ad-hoc call), then `headers`, then the copy. Spring adds default and request headers before `execute()`
  (round 1 F1, bytecode); the copy runs at `execute()` and sets a name only when the request has none. Test: the
  same name from all three sources gives one value, the method's; from `headers` and the caller, the configured one.
- **Forbidden names (ADR 3.4), `SX-CORE-1003`, rule `headers-allowed`.** `Authorization`, `Cookie`, `Set-Cookie`,
  `Proxy-Authorization`, and the client's API-key header when `auth.api-key` is set, are refused in `headers` or
  `copy-headers`, under `defaults` or a client, without case, on each client's merged settings. The message names
  the client, the key and the header name; never a value.
- **Map keys.** To keep a name's case, the docs show the bracket form (`headers.[X-Partner-Id]`); header names are
  case-insensitive on the wire. Not verified: the binding test proves both forms in `.properties` and YAML.
- The headers page says in one line that `defaults.copy-headers` also reaches third-party clients (judge note).

### Copied headers
- Read from `RequestContextHolder` on the caller's thread at `execute()`. `FrameworkServlet` sets it for every
  MVC request, and Boot's `requestContextFilter` covers filters before it (judge, 4.1.2 bytecode). On a scheduled
  job, an `@Async` thread or a service without the servlet API, nothing is copied and nothing is sent (A9). A
  company's shared `defaults.copy-headers` therefore never stops a non-web service.
- All values of a name are copied; their size is bounded by the server's header limit.

### Interceptors (ADR 1.3)
```java
public interface ClientInterceptor {          // API
  void beforeSend(OutgoingRequest request);
}
public interface GlobalClientInterceptor extends ClientInterceptor {}   // API

public interface OutgoingRequest {            // API; the starter's DefaultOutgoingRequest is internal
  String client();                            // the client's name
  String method();
  URI uri();
  Optional<String> header(String name);
  void setHeader(String name, String value);
  Optional<byte[]> body();                    // see "Bodies"
}
```
- `@StratumClient(value = "orders", interceptors = OrderSigner.class)` names beans by type. `ClientInterceptors`
  collects, per client name, the classes named on that client's annotated interfaces (the registrar already reads
  each annotation), joined. `StratumClientBuilders.configure(client, builder)` looks them up there, so they run on
  every `RestClient` built for that name: the group's, and the one `DefaultStratumHttpClients` builds for
  `restClient(name)` (each is a separate `RestClient`; both go through `configure`). A named class with no bean,
  or two, stops startup with Spring's own bean error.
- Every `GlobalClientInterceptor` bean runs on every client, before the client's own, in `@Order` order.
- **Clients with no `@StratumClient`** (a generated client named in `interfaces`, ADR 0012, or a client used only
  through `restClient(name)`): a `GlobalClientInterceptor` that checks `request.client()`. The docs show it, and
  `docs/modules/http-client.md` lines 224 and 297-298, which point to a buffering request interceptor in a
  `StratumClientCustomizer`, point to `GlobalClientInterceptor` instead. Test: an `interfaces` client case.
- An interceptor's exception fails the call before anything is sent. B1's breaker and retry count only I/O, 5xx
  and 429, so it is neither retried nor counted (judge note).
- `DefaultOutgoingRequest.toString` shows the client, method and path only (ST-SEC-02).

### The wrapper (round 1 F2)
- `configure` sets the client's request factory (the pool's, or C3's `MockRequestFactory` when `mock: true`), then,
  when the client has `copy-headers` or any interceptor, wraps that factory in `OutgoingStepsRequestFactory`, then
  applies the team's `StratumClientCustomizer`s. `RestClient.Builder` has no getter for its factory (7.0.10), so
  the wrapper cannot be added after a customizer that replaces the factory; such a customizer replaces the pool
  too, as today, and the docs say so.
- The wrapping request's `getHeaders()` and `getAttributes()` return the delegate's, so Spring's attributes,
  C3's `stratum.mock.method` included, land on the real request. The body goes on by `setBody` when the delegate
  is a `StreamingHttpOutputMessage`, else is written to its `getBody()`.
- `execute()`: copy step, then global interceptors, then the client's, then the delegate's `execute()`. Spring
  creates a new request per attempt and runs initializers at creation, so this is after auth and per attempt.
- Test: a global interceptor with `mock: true` gets the mock's answer from the file, and the interceptor ran.

### Bodies (round 1 F3)
- `StreamedBodyMarker`, an `HttpRequestValues.Processor` set per group through the proxy-factory hook B1 adds,
  reads the `@RequestBody` argument and sets the request attribute `stratum.body.readable` only when B1's
  `StreamedBody` says it is not streamed. A `@RequestPart` (multipart) body is never marked.
- `body()` returns bytes only when that attribute is set and the `Body` is repeatable: it writes it once, on the
  first call, and the same bytes are sent. Otherwise `body()` is empty and nothing is read, so a
  `FileSystemResource` or `InputStream` upload still streams (ADR 7).
- On `restClient(name)` calls there is no method argument to check, so `body()` is empty; the team holds the body
  there already. The Javadoc and the docs say so.
- Tests: `body()` holds the JSON sent; a `FileSystemResource` upload with an interceptor that calls `body()`
  arrives chunked and `body()` is empty; an ad-hoc call's `body()` is empty.

### Library facts (spring-web 7.0.10, `javap`)
- `AbstractHttpMessageConverter.write` and `AbstractSmartHttpMessageConverter.write` call `setBody` on a
  `StreamingHttpOutputMessage`; `supportsRepeatableWrites` is true for Jackson, `byte[]` and `String`, and for
  every `Resource` but `InputStreamResource` (judge), so repeatability alone cannot tell a file from JSON.
- `FormHttpMessageConverter.writeForm` calls `setBody(byte[])`; multipart uses `setBody(Body)` (judge).
- `DefaultRestClient` order: headers, `createRequest` (factory, initializers), `getHeaders().addAll`,
  `getAttributes().putAll`, body, `execute()` (judge's probe).
- `HttpRequestValues.Processor.process(Method, MethodParameter[], Object[], Builder)` and
  `Builder.addAttribute` exist; `HttpServiceProxyFactory.Builder.httpRequestValuesProcessor` sets it.
- `RestClient.Builder` has `requestFactory(...)` but no getter.

### The eight parameters (ST-AG-14)
- **Speed and throughput:** no wrapper and no extra work for a client without copy or interceptors; static
  headers are set once on the builder. Otherwise one list walk and one map lookup per attempt.
- **Memory and leaks:** a body copy only when an interceptor reads a body marked readable, held for that request.
- **Log volume:** nothing new is logged.
- **Data leaks and security:** forbidden names keep users' identities out of headers; values never appear in
  errors or `toString`; copying a header the caller controls (such as `X-Forwarded-For`) is the team's choice,
  and the docs say so.
- **Maintainability and scalability:** the API holds no Spring type, so the reactive starter can share it.

### Tests (planned)
| What | Test |
|---|---|
| Merge: client wins without case, empty value drops, lists joined | `HttpClientPropertiesTest$Headers` |
| Each forbidden name, in each key, under `defaults` and a client, and the API-key header | `HttpClientPropertiesTest$InvalidSettings`, data in a `@FieldSource` constant |
| Properties, YAML and Java binding, bracket and plain names | `HttpClientPropertiesTest` |
| Static headers on interface and ad-hoc calls; other clients get none; one value per name | `HeadersIntegrationTest` |
| Copied from a real MVC request; nothing outside a request | `CopyHeadersIntegrationTest` |
| Global and client interceptors, order, after auth, client scope, ad-hoc and `interfaces` clients | `InterceptorIntegrationTest` |
| Bodies: JSON readable; file upload chunked and `body()` empty; ad-hoc empty | `InterceptorIntegrationTest$Body` |
| Wrapper with `mock: true` | `InterceptorIntegrationTest$Mocked` |
| An interceptor's exception: nothing sent | `InterceptorIntegrationTest` |
| No copy and no interceptor: no wrapper | `StratumClientBuildersTest` |

### Docs
`docs/modules/http-client.md` (key table, merge rules, lines 224 and 297-298), a new
`docs/modules/http-client-headers.md` (both formats, Java, interceptors, forbidden names, bodies, third-party
note), `docs/configuration/reference.md`, `docs/README.md`.

## Changes from round 1
| Finding | Change |
|---|---|
| F1 copied header sent twice | The copy moved from an initializer to the wrapper's `execute()`; precedence and test in "Keys" |
| F2 wrapper hides attributes, headers, body from the mock | "The wrapper": delegate's headers and attributes, body handed on, wraps pool or mock, test with `mock: true` |
| F3 `body()` reads a file | "Bodies": readable only when marked by `StreamedBodyMarker` from B1's `StreamedBody`; file-upload test |
| F4 no route without `@StratumClient` | `GlobalClientInterceptor` checking `client()`; doc lines 224, 297-298; `interfaces` test |
| F5 one `RestClient` wording | Corrected; `ClientInterceptors` feeds `configure` |
| F6 `OutgoingRequest` internal | API; only `DefaultOutgoingRequest` is internal |
| F7 module and package | "Where each part lives" |
| Coordinator 15:26Z | `trace-headers` to B3; every-attempt test in B1; attributes for C3 |
