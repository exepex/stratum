# Judge: A2 headers and interceptors design note, round 2

Note: `design/http-client-libraries/a2-headers-interceptors-design-note.md` (186 lines), round 2. Base `main` at
`4a3d942`. Feature track (untrusted caller headers), design-note review, steps 1, 2, 3 and 5. Judge model: Fable 5.1.
Round 1: `reviews/judge/A2-headers-note.md` (Opus, FAIL, seven findings).

**PASS.** All seven round-1 fixes hold against the library and the code on `main`; I found no new defect. Four
notes for the code judge below.

I read AGENTS.md and the six topic files, the round-1 verdict and note, the B1 and C3 (r2) notes, and on `main`
`StratumClientGroups`, `StratumClientBuilders` and `docs/modules/http-client.md`. Library facts: spring-web 7.0.10,
read with JDK 21 `javap` from the Maven Central jar. I ran no probe: each claim I
checked is a method signature or a bytecode call sequence, or was proven by the round-1 probe.

## Gap list

| Requirement | Result | Evidence |
|---|---|---|
| ADR 1.3: client and global interceptors, after auth, every attempt, every client | MET | Wrapper at `execute()`; B1 retry re-enters the adapter so a new request is created per attempt; `interfaces` clients reach `configure` (`StratumClientGroups.isStratumClient` Javadoc names `interfaces`) |
| ADR 2.1 / A15: merge, client wins without case, empty drops, lists joined | MET | "Keys"; `HttpClientPropertiesTest$Headers` |
| One value per name on the wire (round 1 F1) | MET | `DefaultRequestBodyUriSpec.initHeaders` builds `new HttpHeaders()`, `putAll(defaults)`, `putAll(request)`: the request replaces a default by name; the copy runs at `execute()`, after Spring's `getHeaders().addAll` (round-1 probe), and only when the name is absent |
| A9: nothing copied outside a request | MET | `RequestContextHolder` on the caller's thread; MVC and job tests |
| ADR 2.4: keys from files and Java, IDE metadata | MET | `ClientSettings` record fields, `HttpClientPropertiesCustomizer` |
| ADR 3.4: forbidden names, API-key header, without case, merged settings | MET | `SX-CORE-1003`, rule `headers-allowed`; message names key and header, never a value |
| ADR 7 / A14: an interceptor sees no streamed body (round 1 F3) | MET | `HttpRequestValues.Processor.process(Method, MethodParameter[], Object[], Builder)` and `Builder.addAttribute` exist; `HttpServiceMethod` runs the processor after `applyArguments`; `RestClientAdapter` copies attributes with `RequestBodySpec.attributes(...)`; `body()` needs both the mark and a repeatable `Body`; ad-hoc calls always empty |
| Wrapper hands attributes, headers and body to the delegate; mock works (round 1 F2) | MET | Delegate's `getHeaders()`/`getAttributes()`; `JdkClientHttpRequest` and `HttpComponentsClientHttpRequest` extend `AbstractStreamingClientHttpRequest`, so `setBody` is the real path, `getBody()` the mock's; test with `mock: true` |
| Wrapper and C3's processor coexist | MET | `HttpServiceProxyFactory.Builder.httpRequestValuesProcessor` does `List.add`; `CompositeHttpRequestValuesProcessor` runs all |
| Route for clients without `@StratumClient`; docs lines (round 1 F4) | MET | `GlobalClientInterceptor` checking `client()`; `http-client.md:224` and `:297-298` are the lines that point to a customizer today; `interfaces` test |
| Wording on `RestClient` per name (F5) | MET | Matches `StratumClientBuilders.configure` on `main`, called by both builders |
| ST-ENG-56 (F6) | MET | `OutgoingRequest` API, `DefaultOutgoingRequest` internal; API holds JDK types only |
| Design-note Q2: module and package (F7) | MET | "Where each part lives"; starter already depends on the library, no cycle |
| Dependency on B1 | MET | `StreamedBody` (`Body`, any `Resource`) is B1's shared check; the per-group hook is Spring's `forEachProxyFactory`, which B1 already uses; code waits for B1's merge |
| Decorator order retry, breaker, resend untouched; `trace-headers` to B3; every-attempt test in B1 | MET | "Not in A2"; A2 adds no decorator |
| No `ClientHttpRequestInterceptor`; no wrapper without copy or interceptor | MET | "The wrapper"; `StratumClientBuildersTest` |
| Secrets and personal data (ST-SEC-01, -02) | MET | `toString` = client, method, path; errors name keys only; `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization` and the API-key header refused in both keys |
| Documents (ST-DOC-04, -05, -09) | MET | Headers page in both formats and Java; `http-client.md` lines named; reference and index |

## Findings
None.

## Planted faults
None: design-note review.

## Notes (for the code judge; none is a defect in the note)
- `AbstractClientHttpRequest.getHeaders()` and `getBody()` are `final` (7.0.10), so the wrapper's request cannot
  extend `AbstractStreamingClientHttpRequest` and still return the delegate's headers. It implements
  `ClientHttpRequest` and `StreamingHttpOutputMessage` itself; its `getBody()` must then still work for a converter
  that does not extend `AbstractHttpMessageConverter` and writes through `getBody()`.
- The copy step should touch `ServletRequestAttributes` only after `RequestContextHolder.getRequestAttributes()`
  returned non-null, and no servlet type may appear in the wrapper's signatures, so a non-web service with a shared
  `defaults.copy-headers` loads no servlet class (the round-1 note said this; round 2 keeps the promise without the
  mechanism).
- A bean that implements `GlobalClientInterceptor` and is also named in `interceptors` runs twice; a
  `ClientInterceptor` bean named on no client never runs. One line each on the headers page.
- A controller that finishes on another thread (`CompletableFuture`, `@Async`) copies nothing, as the note says
  for jobs; the headers page could name that case too.
