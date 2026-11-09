# Design note: H1, the blocking HTTP client starter on ADR 0011 (Feature track), round 3

**Item:** "H1 rework (PR #79): the base, the settings block, the pool, retry off and the README" (ADR 0011,
Follow-up items). Owner's go: 2026-11-09 01:50Z, card "Start the HTTP client starter rework (PR #79) now?".
Built on a fresh branch from `main` (b509a9f). PR #79 is 335 commits behind `main` (`git rev-list --count
0978a0a..origin/main`) and built on the old key model,
so its reusable parts are ported, not merged. It is superseded once this PR opens; the owner closes it.

**In H1:**
- `@StratumClient("<name>")` interfaces, found by scanning, each its own Spring HTTP service group (ADR 1.2);
- the keys `url`, `connect-timeout` 2s, `timeout` 5s, `max-connections` 50, `max-wait` 2s, `idle-timeout` 30s,
  `connection-lifetime` 5m and `shared-pool` false, under `defaults` and `clients.<name>` (ADR 2.1, 2.4);
- an Apache HC5 pool per client, with Apache's retry off, the 2s stale check, `Keep-Alive` honoured, redirects off
  and no network at startup (ADR 4.1, 5.1 to 5.5);
- `HttpClientPropertiesCustomizer` and the `StratumClientCustomizer` bean (ADR 1.4);
- two startup stops: a Boot `spring.http.serviceclient.<name>.*` key set for a Stratum client (ADR 2.2), and a
  `Mono` or `Flux` method without the reactive starter (ADR 1.1);
- the starter README, `docs/modules/http-client.md` rewritten, and the error-code page.

**Later items, not H1:** auth, `ssl-bundle`, `urls`, resilience (`retries`, `retry-delay`, `total-timeout`, breaker),
headers, errors and `DownstreamException`, the call log and pool metrics, streaming, mocks, `interface` and
`stratumHttpClients`, interceptors, and the reactive starter.

## 1. Who uses it, and does anything change for them?
Service teams that call other services, on the `stratum-starter-microservice` bundle or the starter alone, with
Stratum as parent or next to a company framework (ADR 0001). A team's own `RestClient` keeps Java's client: Stratum
defaults `spring.http.clients.imperative.factory=jdk` (owner, 07:11Z). Use: an interface plus `clients.<name>.url`.

## 2. How is it built, and why this way?
Spring's `AbstractHttpServiceRegistrar.forGroup` and `RestClientHttpServiceGroupConfigurer.forEachClient`, with
Boot's `ClientHttpRequestFactoryBuilder.httpComponents()` (keeps the JVM proxy). Both shown in the spike. The
alternative, Boot's own groups and keys (PR #79), was rejected: the spike showed they are ignored.

## 3. Is it safe and fast?
Every call is bounded by a timeout, the pool wait and the pool size; pools close with the context. Bad settings stop
startup without showing a value. No input is parsed; Spring encodes URL values. No thread hop: a blocking call runs
on the caller's thread, so the logging and tracing context stays with it (ST-ENG-53).

## 4. Which rules and owner decisions does it follow?
ADR 0011 decisions 1, 2, 4.1 and 5; ST-ENG-10, -11, -24, -25, -34, -51, -52, -56; ST-SEC-02, -03 and -17; ST-DOC-01,
-05 and -08; ST-TEST-01. The owner decisions (`gap-review-merged.md`) and the spike facts (`spike-result.md`) each
have a test, listed in "Decisions and their tests" below.

## Details

### Modules and packages
- `libs/stratum-http-client`, package `com.exepex.stratum.httpclient`:
  - `@StratumClient` (API);
  - `HttpClientProperties`, a record with `defaults` and a `clients` map of `ClientSettings`;
  - `HttpClientPropertiesCustomizer`, in `spring.factories`;
  - `StratumClientCustomizer` (API), a bean that gets `(String client, RestClient.Builder builder)`.
- `stratum-starter-http-client`:
  - `restclient` package: the registrar, the group configurer, the pool builder and the startup checks;
  - `restclient.autoconfigure` package.
- Dependencies go starter → lib → `stratum-core`, as in PR #79's ArchUnit tests, so there is no package cycle.
- New dependency: `httpclient5`, managed by Spring Boot, as in PR #79. Spring Security and Resilience4j are not added.
- The reactive bundle drops `stratum-starter-http-client` until the reactive starter exists. Otherwise a WebFlux
  service would get a blocking client and Apache HC5.

### API and internal types (ST-ENG-56)
- **API:** `@StratumClient`, `HttpClientPropertiesCustomizer`, `HttpClientProperties`, `ClientSettings` and their
  builders, and `StratumClientCustomizer`. These are what a service needs to build a replacement, as with
  `WebProperties` (`WebArchTest`).
- **`@StratumInternal`:** every other public type: the registrar, the group configurer, the pool builder, the startup
  checks and the auto-configuration. The ArchUnit test checks the list.

### Scanning
- The base packages come from `AutoConfigurationPackages`, as Spring Data does.
- Only interfaces with `@StratumClient` are registered.
- Two interfaces with the same name join one group.
- A client name must match `[a-z][a-z0-9-]*`, so that it is a valid key part. Any other name stops startup with
  `SX-HTTPC-1003`, naming the interface, never a value.

### Keys and real cases
| Key | Default | A real case that changes it |
|---|---|---|
| `url` | required | Every client sets it. Example: `http://inventory:8080` |
| `connect-timeout` | 2s | A downstream in another region |
| `timeout` | 5s | A report endpoint that takes 20s |
| `max-connections` | 50 | A high-volume downstream |
| `max-wait` | 2s | Fail sooner under load |
| `idle-timeout` | 30s | A load balancer that drops idle connections at 20s |
| `connection-lifetime` | 5m | Follow DNS changes faster |
| `shared-pool` | false | Many small clients that call one host |

- There is no Spring Boot key for these. ST-ENG-34's exception applies.
- One Boot key gets a Stratum default: `spring.http.clients.imperative.factory=jdk` (owner, 07:11Z), so teams' own
  clients keep Java's client. The module page lists it with Boot's default (detected, Apache first).
- `timeout` is the response timeout of one attempt. `total-timeout` arrives with resilience.
- Stratum no longer sets Boot's global timeouts (`spring.http.clients.connect-timeout`, `read-timeout`), unlike
  PR #79. This follows ADR 9: Stratum never changes how a client it did not build behaves. The `factory=jdk`
  default above keeps that true.

### Startup checks
- **`SX-HTTPC-1001`:** a Boot `spring.http.serviceclient.<name>.*` key is set for a Stratum client. The message names
  the Stratum key.
- **`SX-HTTPC-1002`:** a `Mono` or `Flux` method is declared without the reactive starter.
- A missing `url` stops startup with `SX-CORE-1003`, naming the key.

Startup stops are numbered from 1001, as in every other module (`error-codes.md`); the ADR's runtime codes keep
0001 to 0004.

The checks read configuration and the interfaces' method return types only. Stratum's own clients are its own
configuration, so the boot-check boundary holds.

### Safe-and-fast, by ST-AG-14
- Speed: one pool lease per call.
- Throughput: each pool is bounded, and a full pool fails after `max-wait`.
- Log volume: H1 adds no log line.
- Memory and leaks: the pools close on shutdown, tested.
- Data leaks: no URL is logged in H1.
- Security: redirects are off, and secrets do not apply (no auth yet).
- Maintainability: Spring's own registrar.
- Scalability: `connection-lifetime`.

### Decisions and their tests
Short durations, as in the spike's `HttpClient5Test`, keep the timing tests fast. Each test fails when its rule is
broken (ST-TEST-01).

| Decision | Source | Test |
|---|---|---|
| Boot `serviceclient` key set for a Stratum client stops startup | ADR 2.2, owner 00:45Z | `StartupChecksTest.bootGroupKeyStopsStartup` |
| `Mono`/`Flux` method without the reactive starter stops startup | ADR 1.1, owner 11-07 08:55Z | `StartupChecksTest.reactiveMethodStopsStartup` |
| Missing `url` stops startup, naming the key | ADR 2.4 | `HttpClientPropertiesTest.missingUrlStopsStartup` |
| `defaults` with a client override, client wins | ADR 2.1 | `HttpClientPropertiesTest.clientValueWins` |
| Java customizer wins over YAML | ADR 1.4, ST-ENG-34 | `HttpClientPropertiesTest.javaWinsOverFiles` |
| `StratumClientCustomizer` changes one client only | ADR 1.4 | `ClientCustomizerIntegrationTest.onlyNamedClientChanged` |
| Each client its own group and pool | ADR 1.2, 5.1, spike 1-2 | `ClientPoolIntegrationTest.eachClientHasItsOwnPool` |
| `shared-pool: true` joins one pool | ADR 5.1 | `ClientPoolIntegrationTest.sharedPoolIsOne` |
| `max-connections` bounds the pool, `max-wait` refuses a call | A2, ADR 5.1 | `ClientPoolIntegrationTest.fullPoolFailsAfterMaxWait` |
| `timeout` and `connect-timeout` | ADR 2.4 | `ClientTimeoutIntegrationTest.readTimeout`, `.connectTimeout` |
| Apache retry off: one call on a 503 | A1, spike finding 1 | `ApacheRetryIntegrationTest.sentOnceOn503` |
| `idle-timeout` closes idle connections | A5 | `ConnectionLifecycleIntegrationTest.idleConnectionClosed` |
| Stale check after about 2s idle | A5, spike 7 | `ConnectionLifecycleIntegrationTest.staleConnectionReplaced` |
| `connection-lifetime` | A4, spike 7 | `ConnectionLifecycleIntegrationTest.oldConnectionNotReused` |
| `Keep-Alive: timeout` honoured | A5, spike 7 | `ConnectionLifecycleIntegrationTest.keepAliveHonoured` |
| Redirects off | ADR 5.4 | `ClientTimeoutIntegrationTest.redirectNotFollowed` |
| No network at startup | A13, ADR 5.5 | `StartupIntegrationTest.startsWithDownstreamDown` |
| Pools close with the context | ADR Consequences | `ClientPoolIntegrationTest.poolsClosedOnShutdown` |
| Module off sets up nothing | ADR 0010 | `RestClientAutoConfigurationTest.moduleOffCreatesNothing` |
| Two interfaces with one name share one group and pool | ADR 1.2 | `ClientPoolIntegrationTest.sameNameSharesGroup` |
| A gzip answer is read | ADR 5.4 | `ClientTimeoutIntegrationTest.gzipAnswerRead` |
| A bad client name stops startup with `SX-HTTPC-1003` | ADR 2.1 | `StartupChecksTest.badClientNameStopsStartup` |
| A team's own `RestClient.Builder` stays on Java's client; the team's `factory` value wins | ADR 9, owner 11-09 07:11Z | `ForeignClientIntegrationTest.plainBuilderKeepsJdkClient`, `.teamFactoryWins` |

### Reused from PR #79
- The constants, properties, customizer and metadata tests;
- the module switch;
- `RestClientConnectionPool`, reshaped per client;
- `DownstreamStub` and `FullPoolCheck`;
- the integration tests for the timeout, the pool and Apache's retry (one call on a 503).

### Documents
- the starter README;
- `docs/modules/http-client.md`, rewritten to ADR 0011 (only H1's keys, the rest marked planned);
- `docs/guides/error-codes.md`;
- `docs/modules/bundles.md`;
- the README line on personal data in a URL (ST-SEC-03);
- ADR 9's sentence on the module page: Stratum does not change a `RestClient` it did not build, so a team's plain
  `RestClient.Builder` has no Stratum timeout;
- the README line that gzip is accepted (ADR 5.4; Apache HC5's default, checked by a test of a gzip answer).

## Round 1 findings
- **F1, accepted:** the API list is under "API and internal types".
- **F2, accepted:** "Decisions and their tests" names a test for each.
- **M1, accepted:** `SX-HTTPC-1001` and `-1002`.
- **M2, accepted:** question 3 states that there is no thread hop.
- **Notes, accepted:** the commit count now gives its command; gzip and ADR 9's sentence are in the documents list.

## Round 2 findings
- **F1, decided by the owner (07:11Z):** Apache HC5 for Stratum's clients only. Stratum sets
  `spring.http.clients.imperative.factory=jdk` as a `StratumDefaults` default (lowest priority, so a team's value
  wins), and a team's own `RestClient` stays on Java's client, as in plain Boot. Question 1 and the test row show it.
  He also weighed Java's client for Stratum's clients and rejected it: no per-client connection limit, pool wait,
  lifetime or pool metrics.
- **M1, accepted:** three rows added (same-name group, gzip, bad name), and a bad name stops startup with
  `SX-HTTPC-1003`.

## Not verified
- JDK 25 (the build runs it);
- whether two interfaces in one group work with a per-group request factory (a test checks it).
