# Design note: C3, mocked clients and `MockClient`, on the blocking HTTP client starter (Feature track), round 1

**Item:** "mocks and `MockClient`" (ADR 0011 section 10, Follow-up items; feature list 10.1 to 10.5). Lane C, on the
owner's message of 2026-11-09 13:26Z. Base: `main` at aabdae8 (H1), and lane B's B1 for the decorator chain: C3's
code starts after B1 merges. Track: Feature (new key, new API).

**Owner decision (2026-11-09 13:39Z, card, option A):** `MockClient` lives in the HTTP client starter, usable only
while `mock: true`. ADR 10 says `stratum-test`, which cannot depend on the HTTP client modules: both already use
`stratum-test` for their own tests (`libs/stratum-http-client/pom.xml:27`,
`starters/stratum-starter-http-client/pom.xml:39`), so Maven would reject the cycle. The C3 pull request corrects
ADR 0011 section 10 to say so.

## 1. Who uses it, and does anything change for them?
Teams whose downstream is not ready (dev, demo) and every team's tests. Nothing changes without `mock: true` (H1 suite
unchanged; the decorator is not in the chain then). Lines: `mock: true` and a file
`src/main/resources/stratum-mocks/payments/createPayment.json`; in tests, `mockClients.of(PaymentClient.class)`.
API: `MockClients`, `MockClient<T>`; internal: `MockExchange`, `MockAnswers`.

## 2. How is it built, and why this way?
A `ClientExchangeDecorator` bean (lane B's chain, innermost, order in `HttpClientConstants`, coordinator 13:34Z)
that answers in place of the HTTP exchange; a Spring `HttpRequestValues.Processor` puts the Java method's name in the
request's attributes, which the decorator reads. Rejected: a fake request factory, which sees only the URL, not the
method, so it cannot pick `<method>.json`. Retry, breaker and lane A's resend still run around the mock.

## 3. Is it safe and fast?
A mock must never hide in production: a WARN at every startup per mocked client (ADR 10). No network at all: lane A's
initializers run when a request is created, which a mock never does, so no token is fetched. Files are read once at
first use and cached per method. Hostile input: none; files come from the service's own classpath.

## 4. Which rules and owner decisions does it follow?
ADR 0011 section 10 and 2.4 (`mock` false); ADR 5.5 (no network at startup); feature list 10.4, 10.5; ST-ENG-34
(the customizer sets `mock`), -56, -57; ST-TEST rules for the helper. Documents: module page (Mock section, Properties,
Startup errors, Planned), IDE metadata.

## Details

### Key
| Key | Default | Real case |
|---|---|---|
| `mock` (under `defaults` and `clients.<name>`) | false | the payments service is not deployed in the demo environment; a test profile |

`url` stays required (H1, `SX-HTTPC-1004`): the block reads the same with the mock off.

### Answers from files (running application)
- `classpath:stratum-mocks/<client>/<method>.json`, read with the application's JSON mapper into the method's return
  type. `void` and `ResponseEntity<Void>` need no file; `ResponseEntity<T>` answers 200 with the body; `Optional<T>`
  with an empty file answers empty; `InputStream` gives the file's bytes.
- Overloaded methods share one file (one name). Error answers are not scripted by files, only by `MockClient`.
- A method with a return value and no file stops startup with `SX-HTTPC-1009` (new), naming the client and the file
  path. This reads Stratum's own setting (`mock: true`) and the classpath only, so it is inside the 11-06 boundary.

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
- `on(method)` with `respond(value)`, `fail(status)` (Spring's own `HttpClientErrorException` or
  `HttpServerErrorException`, so lane B's retry, breaker and error rules treat it as a real answer),
  `delay(duration)`. A delay longer than the client's `timeout` fails like a real read timeout, after `timeout`.
- `calls(method)` returns the arguments of each call. A scripted answer wins over the file; `reset()` clears scripts
  and calls. `@MockitoBean` stays an option for unit tests; `MockClient` is for tests that want Stratum's retry,
  breaker and timeouts around the fake answer.
- `of(type)` for a type whose client is not mocked stops the test with a clear message.

### Decisions and their tests
| Decision | Test | Planted fault |
|---|---|---|
| `mock: true` answers from the file, no connection | IT with `SilentPort` URL: answer equals file, port saw no connection | decorator not added |
| WARN at every startup per mocked client | `LogLineCapture` IT | log removed |
| No token fetch on a mocked client | IT once lane A's auth exists (else note) | - |
| Missing file stops startup | `StartupChecksTest` 1007 | check removed |
| `mock: false` leaves the chain unchanged | `StratumClientGroups` test: no mock decorator | condition inverted |
| Scripted answer wins; `calls` records; `fail(503)` is retried by B1's rules | `MockClientIntegrationTest` | script ignored |
| A delay beyond `timeout` fails like a read timeout | same | delay ignored |
| Java customizer sets `mock` | `HttpClientPropertiesTest` | - |

### Safe and fast (ST-AG-14)
Speed: a map lookup and a cached parse per call. Memory: answers cached per method of mocked clients only; calls
recorded only in `MockClient` (tests). Log volume: one WARN per mocked client per startup. Data leaks: no file content
logged. Security: a mock in production is loud (WARN), not silent. Maintainability: one decorator in lane B's chain.

### Not in C3
A mocked client in a metric or actuator info (planned issue, ADR follow-ups); the reactive starter's mocks.

## Not verified
That Jackson 3's mapper in Boot 4.1.2 reads into a `ParameterizedTypeReference`'s type without extra set-up (first
test before code); the order constant B1 picks for the mock.
