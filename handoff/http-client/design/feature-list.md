# HTTP client starters: feature list

Date: 2026-11-07. Built from Aashish's requirements (11:20Z message) plus earlier decisions in this thread (survey.md
sections 10-12) and Claude's additions. Names of keys, annotations and classes are proposals; nothing is built.

Status: **Agreed** = decided earlier on a card; **Asked** = in Aashish's 11:20Z list; **Added** = Claude's addition,
needs his OK; **Open** = needs a decision.

## 1. Creating and using clients

| # | Feature | Example | Status |
|---|---|---|---|
| 1.1 | Any number of named clients, all from config | `stratum.http-client.clients.<name>` | Agreed |
| 1.2 | One block per client, `defaults` for all; client wins over defaults; Java customizer wins over files | `defaults.read-timeout: 5s`, `clients.orders.read-timeout: 3s` | Agreed (needs ST-ENG-34 exception) |
| 1.3 | `@StratumClient("orders")` interface, business-named methods, short verb annotation | `@Post("/orders") Order createOrder(NewOrder o)` | Agreed |
| 1.4 | Blocking starter + reactive starter (`Mono`/`Flux`), same keys | | Agreed |
| 1.5 | Ad-hoc call with the same settings, no interface | `stratumHttpClients.restClient("orders")` | Agreed |
| 1.6 | Clear startup error for Stratum's own config mistakes (missing block, unknown auth type) | | Agreed |

## 2. Connections and timeouts

| # | Feature | Example | Status |
|---|---|---|---|
| 2.1 | Connect and read timeout, global and per client | `connect-timeout: 2s`, `read-timeout: 5s` | Agreed |
| 2.2 | Total deadline for one call, all retries included | `total-timeout: 10s` | Added |
| 2.3 | Own connection pool per client by default; `pool.shared: true` joins one shared pool | `pool.shared: true` | Agreed 2026-11-07 11:24Z |
| 2.4 | Pool size, wait for a free connection, fail fast when full | `pool.max-connections: 50`, `pool.max-wait: 2s` | Agreed (PR #79) |
| 2.5 | Connection lifetime and idle eviction (follows DNS and pod changes) | `pool.max-connection-age: 5m` | Added |
| 2.6 | Redirects off, always | | Asked + agreed |
| 2.7 | Gzip responses accepted | | Added |
| 2.8 | Response size cap, streaming for large up/downloads | `max-response-size: 10MB` | Added |
| 2.9 | Pool metrics on both stacks | | Added (blocking missing since H1) |

## 3. Where calls go

| # | Feature | Example | Status |
|---|---|---|---|
| 3.1 | Direct URL | `base-url: https://orders.internal` | Agreed |
| 3.2 | Client-side load balancing over a fixed list | `base-url: lb://orders`, `addresses: [...]` | Agreed |
| 3.3 | Service discovery sources (Eureka, Consul, Kubernetes API) | `address-source: eureka` | Asked; Agreed as planned issue |
| 3.4 | Internal vs external client: one switch that sets safe defaults for third parties | `audience: external` | Added (see 5) |

## 4. Resilience

| # | Feature | Example | Status |
|---|---|---|---|
| 4.1 | Retry on/off, global and per client; safe methods and brief failures only | `retry.enabled: true` | Agreed |
| 4.2 | Max retries configurable 0-9, default 3 | `retry.max-retries: 5` | Asked (Claude reads "4 to 9" as "allow up to 9") |
| 4.3 | Back-off: fixed, exponential, exponential with jitter; max delay | `retry.backoff: exponential`, `retry.initial-delay: 200ms`, `retry.max-delay: 2s` | Asked |
| 4.4 | Honour `Retry-After` on 429 / 503 | | Added |
| 4.5 | Circuit breaker, global and per client | `circuit-breaker.failure-rate-threshold: 50%` | Agreed |
| 4.6 | Retry also for POST only when the team marks it safe, with an idempotency key header | `@Post(value = "/payments", retrySafe = true)` | Added (planned issue) |
| 4.7 | Bulkhead, rate limit, hedging, fallback | | Agreed as planned issues |

## 5. Headers, tokens and what leaves the company

| # | Feature | Example | Status |
|---|---|---|---|
| 5.1 | Trace and correlation ID sent on every internal call | | Asked + agreed (H2) |
| 5.2 | Token relay: the caller's validated token is sent on | `auth.type: token-relay` | Asked + agreed |
| 5.3 | Service-to-service: the service's own token (client credentials) | `auth.type: client-credentials` | Agreed |
| 5.4 | Service's own token **and** the user's token together (peer call) | `auth: { type: client-credentials, relay-user-token-as: X-User-Token }` | Asked, Claude's reading |
| 5.5 | Basic, bearer from secret, API key, mTLS | | Agreed |
| 5.6 | Forward chosen headers from the incoming request (allow list), global and per client | `forward-headers: [X-Channel, X-Device-Id]` | Asked + agreed (H2) |
| 5.7 | Deny list per client, wins over allow list | `deny-headers: [X-Internal-Debug]` | Asked |
| 5.8 | `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization`, `X-Api-Key` never forwarded by header lists | listing one stops startup | Agreed (H2) |
| 5.9 | Static extra headers per client | `headers: { X-Source: billing-service }` | Asked |
| 5.10 | Dynamic headers from a named bean (computed per call) | `headers: { X-Signature: "bean:requestSigner" }` + `StratumHeaderProvider` bean | Asked |
| 5.11 | Tenant ID forwarded when multi-tenancy is on, off per client | `forward-tenant-id: false` | Asked (needs a tenancy source; Stratum has none yet) |
| 5.12 | **External clients** (`audience: external`): no token relay, no forwarded headers, no tenant ID, no trace or correlation headers, HTTPS only | | Added: one switch instead of many deny lists |
| 5.13 | Per-method header parameter | `Order createOrder(NewOrder o, @Header("X-Request-Id") String requestId)` | Added |

## 6. Security

| # | Feature | Status |
|---|---|---|
| 6.1 | Base URL and hosts only from config, never from request data (SSRF) | Added |
| 6.2 | Path and query values always encoded (`../`, `?`, `#` in an ID cannot change the path) | Added |
| 6.3 | Header values with CR/LF rejected (header injection) | Added |
| 6.4 | Redirects off (6.4 = 2.6) | Agreed |
| 6.5 | Response size cap (2.8) and no polymorphic JSON typing (deserialization attacks) | Added |
| 6.6 | TLS verification always on; plain `http` refused for external clients | Added |
| 6.7 | Secrets only from placeholders; never in logs, metrics or spans | Agreed |

## 7. Observability, logging and debug

| # | Feature | Status |
|---|---|---|
| 7.1 | Span and timer per call, tagged with client name; retry and breaker metrics | Agreed |
| 7.2 | Call log: one line per failed call by default (none, errors, all) | Agreed (H4) |
| 7.3 | Debug per client or all clients via log level, live, no redeploy | Agreed |
| 7.4 | Debug logs bodies (masked) and header names | Agreed |
| 7.5 | **No line cap**: when debug is on, everything is logged | Asked: replaces the 100 lines/s cap proposed at 10:44Z |
| 7.6 | Debug turns itself off after a time: default 15 min, configurable longer | Asked (reuses logging PR 4 timed switch) |

## 8. Errors

| # | Feature | Status |
|---|---|---|
| 8.1 | Downstream answers with Stratum's error body: client throws one Stratum exception carrying its code, status, traceId, client and endpoint | Asked |
| 8.2 | Any other error body: same exception type, generic code, body kept (size-capped, masked in logs) | Added |
| 8.3 | Timeout 504 / unavailable 503 codes (SX-HTTPC-0001/0002) | Agreed (H3) |
| 8.4 | `Optional` return = 404 becomes empty | Agreed |
| 8.5 | Per-client hook to map errors to the team's own exception | Added |

## 9. Extending

| # | Feature | Example | Status |
|---|---|---|---|
| 9.1 | Interceptor for one client or all | `@StratumClientInterceptor("orders") class AuditInterceptor implements ...` | Asked + liked |
| 9.2 | Java customizer per client | `HttpClientPropertiesCustomizer` | Agreed |
| 9.3 | Replace any Stratum bean | | Agreed (ST-ENG rule) |

## 10. Mock clients

| # | Feature | Example | Status |
|---|---|---|---|
| 10.1 | Any client switched to a mock by config, same interface, no code change | `clients.payments.mock.enabled: true` | Asked |
| 10.2 | Mock answers from files per method | `mock.responses: classpath:mocks/payments/` with `createPayment.json` | Asked |
| 10.3 | Works in tests and in running apps (downstream not ready yet) | | Asked |
| 10.4 | A mocked client logs a WARN at every startup, naming the client | | Added (so a mock can't hide in production) |
| 10.5 | Test helpers: assert calls made to a mock, simulate slow and failing answers | `stratum-test` | Added |

## 11. Planned issues (not first release)

Discovery sources (3.3); token exchange; JWT-bearer grant; generate interfaces from YAML; generate from OpenAPI;
hedging; bulkhead and rate limit; fallback methods; before-call check hook (bank case); Spring Cloud adapter;
secret rotation; idempotency-key POST retry (4.6); per-client proxy.

## 12. Pushback and notes

1. 7.5 (no line cap) is accepted: a team's deliberate choice wins (ST-ENG-35). Risk: "debug all" on a busy service
   can multiply log volume and cost many times; the 15-minute auto-off (7.6) is the guard.
2. 5.12 replaces your "filter internal headers for third parties" with one switch, so teams don't maintain deny lists
   for every external client. Deny lists (5.7) still exist for anything extra.
3. 5.11 needs a place the tenant ID comes from (a header or the token). Stratum has no tenancy module yet; the client
   would read it from a configured incoming header until one exists.
4. 4.2: I read "4 to 9" as "allow up to 9 retries". Above 9 would stop startup.

## 13. Revised after Aashish's 19:39Z feedback (proposal, replaces 5.6-5.12 and 8.1-8.5)

### 13.1 Headers: one `headers` map, each value says where it comes from
```yaml
stratum.http-client.defaults.headers:            # every internal client gets these
  X-Correlation-Id: from-request:X-Correlation-Id
stratum.http-client.clients.partner-bank:
  audience: external
  headers:
    X-Client-Name: acme-shop                     # fixed text
    X-Partner-Ref: from-request:X-Order-Ref      # copied if the incoming request has it, else left out
    X-Request-Id: generated:uuid                 # new value on every call
    X-Signature: bean:partnerSigner              # a team bean computes it per call
    Y-Tenant-Id: from-request:X-Tenant-Id        # left = name the target wants, right = where ours comes from
```
- Replaces `forward-headers`, `deny-headers`, `send-correlation-id`, `forward-tenant-id`. One place, one rule.
- Not `${...}`: Spring resolves `${...}` when the file is read, before any request exists.
- `audience: external` now means only: do not inherit `defaults.headers`, do not send trace headers, no token
  relay, HTTPS only. Anything the vendor needs is listed in its own `headers`.
- `from-request:` can never copy `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization`, `X-Api-Key`
  (startup stops); tokens go through `auth`. Values with line breaks are rejected.
- Anything else: `@StratumClientInterceptor("partner-bank")`.

### 13.2 Errors: no field mapping in config
- Every non-2xx answer becomes one `DownstreamException`: status, client, endpoint, a code by status class, raw body
  (size-capped). Works for every service with no setup.
- A Stratum service (or any RFC 9457 `problem+json`) is read automatically: code, message, list of details, traceId.
- A vendor's own format: the team writes it as a Java record, nested objects and arrays included, and names it on
  the interface; Jackson reads it.
  `@StratumClient(value = "payments", errorBody = PaymentsError.class)` -> `e.errorBody(PaymentsError.class)`.
- Optional per-client `StratumClientErrorMapper` bean turns it into the team's own code (one Java method), for teams
  that want it.
- Our API's caller always gets Stratum's error body, never the vendor's.

### 13.3 Tokens (auth.type)
| Kind | Example | Sent |
|---|---|---|
| Service | nightly billing job calls invoice-service, no user | `Authorization`: billing-service's own token |
| Token relay | Ravi -> order-service -> profile-service | `Authorization`: Ravi's token |
| On behalf of, two tokens | order-service charges Ravi's card at payments | `Authorization`: order-service's token, `X-User-Token`: Ravi's token |
| On behalf of, one composite token | same | one token from the identity server with `sub` = Ravi (requester) and `act` = order-service (executor), via OAuth2 token exchange (RFC 8693) |
Provider support for token exchange differs (Entra uses its own on-behalf-of grant); not verified per provider.
The receiving side is the security starter's open on-behalf-of topic; both must agree.

### 13.4 Tenant ID
No tenant feature in the HTTP client. A multi-tenant service lists it in `headers` like any other header, with the
target's name on the left (13.1). When a tenancy module exists, it adds a source `tenant:` (from its context or
token), still configured in `headers`.

## 14. Topic 1 redone after Aashish's 19:59Z feedback (three independent architects, 2026-11-07)

All three dropped: the value-source map, `audience`, deny lists, host guessing. All three: fixed headers in YAML,
code in one interceptor type linked by class from the interface, tokens only via `auth`. Proposal:

| Header kind | Where | Example |
|---|---|---|
| Fixed text | YAML `headers` | `headers: { X-Api-Version: "2" }` |
| Same as an incoming header | YAML `copy-headers` (names only, per client) | `copy-headers: [X-Order-Ref]` |
| From a method argument | `@Header` parameter | `Order find(String id, @Header("X-Locale") String locale)` |
| Generated or computed | interceptor class | `@StratumClient(value = "orders", interceptors = OrderSigner.class)` |

- `@StratumClientInterceptor` class implements one small interface (`intercept(OutgoingCall call)`: client name,
  method, URI, headers, incoming headers). Missing bean -> startup error naming client and class.
  `@StratumClientInterceptor(allClients = true)` for platform-wide ones.
- Trace context + `X-Correlation-Id`: sent by default (2 of 3 architects); `send-trace-context: false` per client
  for a third party that must not get them. Open with Aashish.
- `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization`, API-key header in `headers`/`copy-headers`: startup stops.
- Architects A and B suggest using Spring's own `@GetExchange`/`@PostExchange` instead of new `@Get`/`@Post`.
