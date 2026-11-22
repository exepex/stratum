# Design note: C3, mocked clients and `MockClient`, on the blocking HTTP client starter (Feature track), round 2

**Item:** "mocks and `MockClient`" (ADR 0011 section 10, Follow-up items; feature list 10.1 to 10.5). Lane C, on the
owner's message of 2026-11-09 13:26Z. Base: `main` at aabdae8 (H1). C3's code starts after B1 (resilience) and C2a
(`StratumClientBuilders`) merge. Track: Feature (new key, new API, a new ADR).

**Owner decision (2026-11-09 13:39Z, card, option A):** `MockClient` lives in the HTTP client starter, usable only
while `mock: true`. ADR 10 says `stratum-test`, which cannot depend on the HTTP client modules: both already use
`stratum-test` for their own tests (`libs/stratum-http-client/pom.xml:27`,
`starters/stratum-starter-http-client/pom.xml:39`), so Maven would reject the cycle.
**Owner decision (2026-11-09 13:45Z, card, F4):** a short new ADR replaces ADR 0011 section 10; ADR 0011 is not edited.
ADR 0012 ("Changes to ADR 0011 found while building") is written in C2a's pull request and records this move too
(owner's 13:49Z decision on the `interfaces` key); C3 writes no ADR. If C3 merges first, C3 writes ADR 0012.

## 1. Who uses it, and does anything change for them?
New, existing and hybrid services, as parent or next to a company framework: teams whose downstream is not ready
(dev, demo) and every team's tests. Nothing changes without `mock: true` (H1 suite unchanged; the client keeps its
pool). Lines: `mock: true` and `src/main/resources/stratum-mocks/payments/createPayment.json`; in tests,
`mockClients.of(PaymentClient.class)`. API: `MockClients`, `MockClient<T>`; internal: `MockRequestFactory`, `MockAnswers`.

## 2. How is it built, and why this way?
A fake `ClientHttpRequestFactory` per mocked client, set by `StratumClientBuilders.configure` (C2a) in place of the
pool; modelled on spring-test's `MockRestServiceServer`, which mocks a `RestClient` the same way. Spring then runs the
team's status handlers and converters on the fake answer, as on a real one. The method name reaches the fake request
through a request attribute (`HttpRequestValues.Processor`; Spring 6.2+ copies attributes onto the request). Rejected:
a decorator in lane B's chain, which skips status handlers and converters (judge F1). Package `restclient`: no cycle.

## 3. Is it safe and fast?
A mock never hides in production: a WARN at every startup per mocked client (ADR 10). No network: the fake factory
opens no connection, and `configure` adds no auth initializer to a mocked client, so no token is fetched. Bytes are
cached per file and parsed per call. Calls are recorded only for a client a test asked for. No hostile input.

## 4. Which rules and owner decisions does it follow?
ADR 0011 section 10, 2.4 (`mock` false), 4.4 (time limits), 5.5 (no network); feature list 10.1 to 10.5; owner
13:39Z; ST-ENG-34 (customizer sets `mock`), -52, -56, -57; documentation.md (ADR never edited). Spring facts: below.
Documents: module page (Mock section, Properties, Startup errors, Planned), IDE metadata, ADR 0012 (see above).

## Details

### Key
| Key | Default | Real case |
|---|---|---|
| `mock` (under `defaults` and `clients.<name>`) | false | the payments service is not deployed in the demo environment; a test profile |

`url` stays required (H1, `SX-HTTPC-1004`): the block reads the same with the mock off.

### How a mocked call runs
1. The proxy's `HttpRequestValues.Processor`, added only to mocked clients' proxy factories (F9), puts the method name
   in the attribute `stratum.mock.method`. Spring copies it onto the request (`RestClientAdapter.java:105`,
   `DefaultRestClient.java:601-602`, spring-web 7.0.10).
2. Lane B's decorators (retry, breaker) and lane A's resend run around the call as for a real client.
3. `MockRequestFactory.createRequest` returns a request whose `execute()` answers, in this order: a script from
   `MockClient`, else the file `classpath:stratum-mocks/<client>/<method>.json` as `200`, `Content-Type:
   application/json`. Spring's converters read it into any return type (`Optional`, `ResponseEntity`, `InputStream`
   included; `HttpServiceMethod.java:416, 454-455`), and the team's status handlers see `fail(status)` answers.
4. The request body the code sent is read and dropped, so streamed uploads behave as with a real server.
5. A mocked call goes through Boot's observation customizer, so it shows in `http.client.requests` like a real one.

### Files and the startup check
- File bytes are read once and cached; each call parses them anew, so a caller that changes a returned object never
  changes the next answer (F8). Overloaded methods share one file.
- A method whose return type needs a body and has no file stops startup with `SX-HTTPC-1009` (reserved, coordinator
  13:40Z), naming the client and the file path. No file needed: `void`, `ResponseEntity<Void>`, `HttpHeaders` (F7).
  This reads Stratum's own `mock: true`, the interface's methods (as `SX-HTTPC-1002` already does) and the
  classpath, so it is inside the 11-06 boundary.

### `MockClient` (tests)
```java
@SpringBootTest(properties = "stratum.http-client.clients.payments.mock=true")
class CheckoutTest {
  @Autowired MockClients mockClients;

  @Test
  void chargesOnce() {
    var payments = mockClients.of(PaymentClient.class);
    payments.on("createPayment").respond(new Payment("p-1", "OK"));
    checkout.checkout(cart());
    assertThat(payments.calls("createPayment")).hasSize(1);
  }
}
```
- `on(method)` with `respond(value)` (written with the application's JSON mapper), `fail(status)` (optional body),
  `delay(duration)`.
- **Delays (F2):** the fake request waits the smaller of the delay and the attempt's limit, min(`timeout`, time left
  under `total-timeout`) read from B1's scoped value, then throws `SocketTimeoutException`, which Spring turns into
  `ResourceAccessException` as for a real read timeout. B1 classifies it the same, and the call ends by
  `total-timeout`.
- **Recording (F3):** calls (method and arguments) are recorded only for a client after a test called `of(type)`. A
  demo app with `mock: true` that never calls `of` records nothing, so nothing grows.
- **Reset (F5):** scripts and records are cleared after every test method by `MockClientsResetListener`, a Spring
  `TestExecutionListener` listed in the starter's `META-INF/spring.factories`, like Spring's own reset of
  `@MockitoBean`. `spring-test` is an optional dependency, so a running app never loads it. `reset()` stays for use
  inside a test.
- `of(type)` for a type whose client is not mocked stops the test with a clear message.

### Decisions and their tests
| Decision | Test | Planted fault |
|---|---|---|
| `mock: true` answers from the file, no connection | IT with `SilentPort` URL: answer equals file, port saw no connection | factory not swapped |
| WARN at every startup per mocked client | `LogLineCapture` IT | log removed |
| No auth initializer on a mocked client | IT with `client-credentials` and an unreachable token URI: call answers | initializer kept |
| Team status handler sees `fail(404)` (F1) | IT: a `StratumClientCustomizer` maps 404 to the team's exception; `fail(404)` throws it | answer bypasses handlers |
| Missing file stops startup; `HttpHeaders` and `void` need none | `StartupChecksTest` 1009 cases | check removed / `HttpHeaders` not excluded |
| `mock: false` keeps the pool | `StratumClientGroups` test | condition inverted |
| Scripted answer wins; `calls` records; `fail(503)` is retried by B1 | `MockClientIntegrationTest` | script ignored |
| Delay beyond the limits: call ends by `total-timeout` | same, `timeout` 300ms, `total-timeout` 500ms, `delay` 10s | delay not capped |
| Nothing recorded without `of(type)` | unit test of `MockAnswers` | always record |
| Scripts cleared after each test | two ordered test methods, the second sees the file answer | listener not listed |
| A returned object changed by the caller does not change the next answer | unit test | parsed object cached |
| Java customizer sets `mock` | `HttpClientPropertiesTest` | - |

### Safe and fast (ST-AG-14)
Speed: a map lookup and one JSON parse per call. Throughput: no pool and no socket, so a mocked client is never the
bottleneck. Log volume: one WARN per mocked client per startup. Memory and leaks: bytes per file, records only after
`of`, cleared after each test. Data leaks: no file content or argument logged. Security: a mock in production is loud
(WARN), never silent. Maintainability: Spring's own converters and handlers, no copy of return-type handling.
Scalability: nothing shared across clients but the read-only file cache.

### Cross-lane
Lane B: no mock decorator in its chain (the 13:34Z slot is not needed); the delay reads B1's time-left value. C2a:
`configure` chooses `MockRequestFactory` and skips auth initializers when `mock: true`. Lane A: none.

### Not in C3
A mocked client in a metric or actuator info (planned issue); the reactive starter's mocks.

## Not verified
That B1 exposes its time-left value to code outside its decorator (to agree with lane B); `MockClientsResetListener`
running under Spring Boot 4.1.2's test context (first test before code).

## Round 1 findings (reviews/judge/C3-mocks-note.md)
F1 fake request factory chosen, with the right fact and a status-handler test; F2 delay capped by the attempt limit;
F3 records only after `of`; F4 owner chose a new ADR 0012 (13:45Z); F5 automatic reset per test; F6 Q1 to Q3 facts and
all eight parameters; F7 code 1009 and `HttpHeaders`; F8 bytes cached, parsed per call; F9 processor on mocked
clients only.
