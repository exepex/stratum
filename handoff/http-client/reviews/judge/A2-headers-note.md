# Judge: A2 headers and interceptors design note (HTTP client starter, lane A step 2)

Note: `design/http-client-libraries/a2-headers-interceptors-design-note.md` (136 lines). Base `main` at `4a3d942`.
Feature track (untrusted caller headers), design-note review, round 1, steps 1, 2, 3 and 5. Judge model: Opus.

**FAIL.** Three blocking findings: copied headers as designed are sent twice, the interceptor wrapper as written
breaks mocked clients, and `body()` would read a whole file upload into memory. Each comes from a library fact I
checked in the jar or ran in a probe.

I read AGENTS.md and all six topic files, ADR 0011 and 0012, the A1 r2 verdict, the B1, C1 and C3 notes, and the
code on `main` (`ClientSettings`, `HttpClientProperties`, `StratumClientBuilders`, `StratumClientGroups`,
`DefaultStratumHttpClients`, `docs/modules/http-client.md`). Library facts: spring-web 7.0.10, spring-webmvc 7.0.10,
spring-boot-webmvc 4.1.2, read with `javap`; one runtime probe on spring-web 7.0.10 (JDK 21).

## Gap list

| Requirement | Result | Evidence |
|---|---|---|
| ADR 1.3: client and global interceptors, after auth, every attempt | PARTLY | Wrapper on the request factory runs at `execute()`, after initializers: right. Clients without `@StratumClient` have no route (F4) |
| ADR 2.1 / A15: `headers` merge, client wins, empty drops; `copy-headers` joined | MET | Merge rules in "Keys"; `HttpClientPropertiesTest$Headers` |
| A9: copy with nothing to copy sends nothing | MET | `RequestContextHolder` on the caller's thread; job and `@Async` cases tested |
| Copied header never duplicates one the request already has | NOT MET | F1 |
| ADR 2.4 keys, files and Java, IDE metadata | MET | Record fields + `HttpClientPropertiesCustomizer`; `ClientSettings.orElse` (`ClientSettings.java:56-66`) needs a map merge, which the note states |
| ADR 3.4: fixed forbidden names incl. API-key header, without case, on merged settings | MET | `SX-CORE-1003` is the existing invalid-settings code (`HttpClientProperties.java:46`); message names key, never value |
| ADR 7 / A14: interceptor sees no body for a streamed upload | NOT MET | F3 |
| No body buffering when no interceptor; no `ClientHttpRequestInterceptor` | MET | Static headers via `defaultHeaders`, no wrapper without interceptors; `StratumClientBuildersTest` |
| Wrapper last, around C3's mock factory, exposing the delegate's attributes (coordinator) | NOT MET | F2 |
| `trace-headers` left to lane B's call log (B3) | MET | "Not in A2" |
| "Every attempt" test lives in B1 | MET | Note defers it to `RetryIntegrationTest.interceptorRunsOnEveryAttempt` |
| ST-ENG-56: API types carry no `@StratumInternal`, expose only API | NOT MET | F6 |
| Design-note Q2: module and package named | PARTLY | F7 |
| Secrets never in errors or `toString` (ST-SEC-01, -02) | MET | `toString` = client, method, path; errors name keys only |
| Documents (ST-DOC-04, -05, -09) | PARTLY | New `http-client-headers.md` with both formats and Java; existing lines that point to a request interceptor not listed (F4) |

## Findings

1. **Blocking: a copied header is sent twice when the request already has it.** The note: "A copied header is set
   after both, but only when the request has none of that name", done by an initializer. In
   `DefaultRestClient$DefaultRequestBodyUriSpec.exchangeInternal` (7.0.10 bytecode) the order is `initHeaders()`,
   then `createRequest(uri)` (factory, then `initializers.forEach`), then `clientRequest.getHeaders().addAll(headers)`,
   then `getAttributes().putAll(...)`. Probe (initializer adds `X-Tenant: copied` if absent; method has
   `@RequestHeader("X-Tenant")`): `INIT sees headers=[] attrs={}`, then `SENT headers=[X-Tenant:"copied", "fromMethod"]`.
   Fix: copy at a point that runs after the request's own and default headers are set, and say which; add a test
   with the same name from `@RequestHeader`, from `headers` and from the caller, asserting one value on the wire.
   (`RestClient.Builder.defaultRequest` may be such a point; I did not verify its timing.)
2. **Blocking: the wrapper must hand Spring's attributes, headers and body to the delegate.** Spring copies the
   request attributes onto the request the factory returned (bytecode above, after `createRequest`), so with the
   wrapper on top they land on the wrapper. C3's fake request reads `stratum.mock.method` from its own attributes
   (C3 note, steps 2-3) and otherwise answers 501. So any `GlobalClientInterceptor` turns every mocked call into a
   501. The note leaves this "to agree with lane C". Fix: the wrapper's `getHeaders()` and `getAttributes()` return
   the delegate's; the body goes on by `setBody` when the delegate is a `StreamingHttpOutputMessage`, else through
   its `getBody()`; the wrapper wraps whichever factory `configure` chose (pool or mock) and is applied after the
   team's `StratumClientCustomizer`s. Test: a global interceptor plus `mock: true` answers from the file and the
   interceptor ran.
3. **Blocking: `body()` reads a file upload into memory.** The note: `supportsRepeatableWrites` is "false by default
   (resources, input streams)". Wrong for resources: `ResourceHttpMessageConverter.supportsRepeatableWrites(Resource)`
   returns `!(r instanceof InputStreamResource)` (7.0.10 bytecode), so a `FileSystemResource` body is repeatable and
   `body()` would copy the whole file to the heap. ADR 7 says an interceptor sees no body for a streamed upload, and
   B1's shared `StreamedBody` counts any `Resource` as streamed. Fix: `body()` is empty for every body `StreamedBody`
   calls streamed, on interface and `restClient(name)` calls; say how the wrapper learns it; test a
   `FileSystemResource` upload with an interceptor that calls `body()`: sent chunked, `body()` empty.
4. **Should fix: clients without `@StratumClient` get no client interceptor, and the docs send teams to a buffering
   one.** A generated client (`interfaces`, ADR 0012) or a `restClient(name)`-only client cannot name interceptors,
   and `docs/modules/http-client.md:224` ("such as a request interceptor ... declare a `StratumClientCustomizer`")
   and `:297-298` point to a customizer, whose `requestInterceptor` buffers every body (ADR 0012). The note does not
   list these lines. Fix: state the route (for example a `GlobalClientInterceptor` that checks `request.client()`),
   document it, update those lines, and add an `interfaces`-client case to `InterceptorIntegrationTest`.
5. **Minor: "one client is one `RestClient`, which also serves `restClient("orders")`" is wrong.**
   `DefaultStratumHttpClients.build` creates its own `RestClient` per name; both go through
   `StratumClientBuilders.configure`. Fix: correct the sentence and name where `configure` gets the interceptor
   classes found on a client's annotated interfaces.
6. **Should fix (ST-ENG-56): `OutgoingRequest` is API but marked internal.** Q1 lists it as API; the code block says
   "implemented by the starter, `@StratumInternal`". It is the parameter of the API method `beforeSend`, so it must
   be API. Fix: `OutgoingRequest` is API; only the starter's implementation carries `@StratumInternal`.
7. **Minor (design-note Q2): module and package not named.** "New types in the existing packages" and "two small
   starter classes". Fix: API types in `libs/stratum-http-client`, `com.exepex.stratum.httpclient`; the wrapper and
   the request implementation in `com.exepex.stratum.restclient`; name the classes, including where the copy and
   the forbidden-name check live.

## Planted faults
None: design-note review (judge.md, steps 1, 2, 3 and 5).

## Notes
- Two "not verified" items are now settled. Forms: `FormHttpMessageConverter.writeForm` calls `setBody(byte[])`,
  whose default `Body` is repeatable, so `body()` sees a form; multipart uses `setBody(Body)`. Copy source:
  `FrameworkServlet` sets `RequestContextHolder` for every MVC request, and Boot's
  `WebMvcAutoConfigurationAdapter.requestContextFilter()` covers filters before it (4.1.2).
- An interceptor's exception is not counted by the breaker under B1's rule (I/O, 5xx, 429 only), which matches
  the note.
- Copying `Host` or another hop-by-hop header would misroute a call; no realistic team asks for it. Theoretical.
- `copy-headers` under `defaults` also reaches third-party clients; the headers page could say so in one line.
