# ADR 0011: HTTP client starters on Spring HTTP interfaces, with one Stratum block per client

**Status:** Accepted, 2026-11-09, by the owner, who took every decision in it from 2026-11-07 to 2026-11-09. It is not built yet: PR #79 (H1) is reworked to it, and the follow-up items build the rest. It changes
three rules: ST-ENG-34, ST-SEC-03 (with ST-SEC-02) and ST-ENG-51 ([Rule changes](#rule-changes)). It replaces the
planned `resilience-policy` link between HTTP clients and the resilience starter
(`docs/modules/resilience.md`).

It replaces no earlier ADR. It uses [ADR 0001](0001-coexistence-with-company-frameworks.md) (a company's bean
wins), [ADR 0006](0006-separate-mvc-and-webflux-starters.md) (separate blocking and reactive starters) and
[ADR 0010](0010-modules-on-when-present-and-one-compatibility-switch.md) (on when present, off with
`stratum.http-client.enabled=false`).

[← Documentation home](../README.md)

## Context

- **Calls to other services are the most common cause of incidents.** A missing timeout, a retry storm, a leaked
  token or a full pool is copied into every service that runs on Stratum (AGENTS.md, "The project").
- **Spring already has the parts.** Spring Framework 7 has HTTP interface clients and service groups. Spring
  Security 7 has OAuth2 client interceptors. Resilience4j 2.4 supports Boot 4. What Spring lacks is a safe default
  for each: Boot sets no timeouts and no pool limits, and Apache HttpClient 5 retries on its own.
- **The survey (2026-11-07)** compared libraries in Java, .NET, Go, Rust, Python and Node. The owner chose to build
  on Spring and to copy features, not other libraries' way of working. Endpoints are never written in YAML.
  Spring Cloud OpenFeign is feature-complete, and Spring Cloud 2025.1 lists Boot 4.0 only.
- **Teams also use clients Stratum did not build:** OpenAPI-generated ones, vendor SDKs and their own engines. The
  owner's requirement (2026-11-08): Stratum integrates with them and never conflicts with them.
- **Two gap reviews and a spike (2026-11-09)** found 21 gaps. The owner decided each one, and a spike on Boot
  4.1.2 checked 9 assumptions. All 9 held. The spike also found that Apache HC5 retries a 503 by itself
  (the spike's tests and output are in this record's pull request).

## Decision

### 1. Two starters, one model
1. `stratum-starter-http-client` (blocking, `RestClient` on Apache HttpClient 5) and
   `stratum-starter-reactive-http-client` (`WebClient` on Reactor Netty). They share `libs/stratum-http-client`
   and the same keys and rules. A `Mono` or `Flux` method without the reactive starter stops startup.
2. A client is a Java interface with `@StratumClient("<name>")`, using Spring's own `@GetExchange`,
   `@PostExchange`, `@PathVariable`, `@RequestBody`, `@RequestParam` and `@RequestHeader`. Interfaces are found
   by scanning, like Spring Data repositories. Each client becomes its own Spring HTTP service group.
3. Interceptors are plain beans: `ClientInterceptor` for the clients named in
   `@StratumClient(interceptors = ...)`, and `GlobalClientInterceptor` for all clients. They run on every attempt,
   after auth.
4. Settings in Java follow ST-ENG-34. `HttpClientPropertiesCustomizer`, listed in `META-INF/spring.factories`,
   sets every key of `defaults` and of each client, and wins over YAML. What has no key goes on a
   `StratumClientCustomizer` bean, which adjusts one client's builder like Spring's `RestClientCustomizer`
   (ST-ENG-24): a custom back-off, retry or breaker rules, or the error mapper. Ad-hoc calls with the same
   settings go through `stratumHttpClients.restClient("<name>")`.

### 2. One Stratum block per client (exception to ST-ENG-34)
1. Every setting of a client, base URL and timeouts included, lives under `stratum.http-client.clients.<name>.*`.
   Shared values live under `stratum.http-client.defaults.*`, and the client's value wins. Two maps merge
   instead: a client's `headers` are added to `defaults.headers`, the client wins per name, and an empty value
   drops a default; `copy-headers` lists are joined. A `copy-headers` name with nothing to copy sends nothing.
2. Spring Boot's `spring.http.serviceclient.<name>.*` keys do not apply to a Stratum client. Stratum builds the
   request factory, and the spike showed Boot's per-group timeout is then ignored. Startup stops when such a key is
   set for a Stratum client's name, naming the Stratum key to use instead (owner, 2026-11-09). It reads
   configuration only.
3. Spring's own blocks stay Spring's. Stratum only names them: `spring.security.oauth2.client.registration.*` for
   client credentials, and `spring.ssl.bundle.*` for mTLS.
4. The keys and their defaults:
   - `url` or `urls` (required, one of them);
   - `auth` (none);
   - `ssl-bundle` (none);
   - `connect-timeout` 2s, `timeout` 5s, `total-timeout` 8s;
   - `retries` 3 (max 9), `retry-delay` 200ms (the first wait, then doubled with jitter, at most 2s, as
     ST-ENG-51 asks);
   - `circuit-breaker` true;
   - `max-connections` 50, `max-wait` 2s, `idle-timeout` 30s, `connection-lifetime` 5m, `shared-pool` false;
   - `headers`, `copy-headers` (none), `trace-headers` true;
   - `mock` false;
   - `interface` (none).

   That is 19 keys, with `url` or `urls` counted as one and the kinds inside `auth` not counted. Java only:
   custom back-off, custom retry and breaker rules, and the error mapper.

### 3. Auth
1. `auth` holds exactly one kind:
   - `token-relay: true`, the caller's token, called "relay" in the 11-06 security decision;
   - `client-credentials: <registration>`, the service's own token, called "service";
   - `api-key` (with optional `api-key-header`);
   - `bearer`;
   - `basic`.

   mTLS goes through `ssl-bundle`. `token-exchange: <registration>` (RFC 8693, "exchange") is planned under the
   same block (#238), and the security starter's on-behalf-of work depends on it. `auth` replaces the 11-06
   example key `clients.<name>.token`.
2. Token relay sends only the access token the security starter has verified, never an ID token or a raw header.
   With no caller token, the call stops before sending with `SX-HTTPC-0004`. `token-relay` without the security
   starter stops startup.
3. Client credentials: on a 401, the cached token is dropped, a new one is fetched, and the call is retried once,
   POST included. A second 401 fails the call and is logged once per client per minute. The token request uses
   the client's timeouts and counts toward `total-timeout`. With token relay, a 401 is passed back unchanged.
4. A user's identity travels only inside a signed token. `Authorization`, `Cookie`, `Set-Cookie`,
   `Proxy-Authorization` and the API-key header are never accepted in `headers` or `copy-headers`. Secrets come only
   from `${...}` placeholders.
5. Stratum does not check the target host of a `URI` method parameter or an absolute ad-hoc URI, and its
   documentation adds nothing about it. The owner rejected both on 2026-11-09: the client's configuration and code
   belong to the team. ST-SEC-17 is a rule for the service's own code, so it is unchanged.

### 4. Resilience: one retry layer, fixed rules
1. Resilience4j, inside the client starters, is the only retry layer (owner, 2026-11-07). Apache HttpClient 5's
   own retry is off, so `retries: 3` means at most 4 attempts. The client starters do not depend on
   `stratum-starter-resilience`, and its switch does not change them (ST-ENG-10, ST-ENG-11). Its planned
   `resilience-policy` key on a client is dropped. When the shared Resilience4j setup moves into a library,
   both use it (ADR 0010, decision 1.4).
2. **Retried:** connect failure, a dropped connection before any answer, a read timeout (GET, PUT and DELETE
   only), 502, 503, 504 and 429. **Never retried:** other 4xx, 500 and 501. POST and PATCH are retried only after a
   connect failure, where nothing was written (owner, 2026-11-09: "only if nothing sent"; ST-ENG-51). Once the
   request was written they are not retried: not after a dropped connection or a read timeout, and not on 502,
   503, 504 or 429.
3. `Retry-After` on 429 or 503 is honoured within the time left. There is no retry while the breaker is open, and
   a streamed upload is never retried.
4. Each attempt's timeout is the smaller of `timeout` and the time left under `total-timeout`. No new attempt
   starts with less than about 100ms left. The default of 8s stays below the web starter's 10s answer timeout.
5. There is one breaker per client. **Counted as failure:** timeouts, I/O errors, 5xx and 429. **Never counted:**
   other 4xx and a 404 read as empty. When open, the call stops with `SX-HTTPC-0003` (503). A state change is
   logged once, and refused calls are counted.

### 5. Connections
1. Each client has its own pool (`shared-pool: true` joins one). After `max-wait`, the call is refused.
2. Idle connections close after `idle-timeout`. A connection idle for more than about 2s is checked before reuse.
   The server's `Keep-Alive: timeout` is honoured.
3. `connection-lifetime` is checked only when a connection returns to or leaves the pool, never during a call.
4. Redirects are never followed. Values are always encoded. Gzip is accepted.
5. Startup does no network work, and readiness never depends on a downstream.

### 6. Errors
1. **Codes** (each also gets a code reference page):
   - `SX-HTTPC-0001`: timeout, 504;
   - `SX-HTTPC-0002`: unavailable, connect failure or a full pool, 503;
   - `SX-HTTPC-0003`: breaker open, 503;
   - `SX-HTTPC-0004`: no caller token for relay, 500 (a setup mistake of the service; owner, 2026-11-09).
2. `DownstreamException` carries the status, client, endpoint, code, trace ID and at most 64 KB of the error body.
   The vendor's body is never passed on to our caller.

### 7. Streaming
All uploads and downloads can stream. A download holds its connection until it is closed. On a download, `timeout`
is the longest gap between bytes. An interceptor sees no body for a streamed upload.

### 8. Observability (exception to ST-SEC-03)
1. The call log line has both the real `url`, with path and query values kept, and the `uri` template. The owner
   decided this on 2026-11-09 to support incident work. The logging starter's always-on redaction runs over it.
   When the logging starter is absent, or present but switched off (`stratum.logging.enabled=false`), there is no
   redaction, so the line carries the `uri` template only, and the documentation says so (owner, 2026-11-09).
   The client starter must learn this without reading the logging starter's settings or importing its classes
   (ST-ENG-11). The call-log item's design note picks the signal. A candidate is a small marker type in
   `stratum-core` that the logging starter registers as a bean only while its redaction runs.
2. Metric tags and span names use only the URI template. The span's URL attribute is the observability
   starter's, unchanged by this record: the full URL with user info removed
   ([observability](../../starters/stratum-starter-observability/README.md)). The owner decided on 2026-11-09
   that span query values are masked like the logs; that is a separate observability item (#240). The rule change
   below covers only the call log line.
3. Pool metrics come from Micrometer's Apache HC5 binder, tagged with the client: max, busy, idle and waiting. Wait
   time and pool-full counts are planned (#237).
4. Body debug (a live log level per client, masked bodies, a 15-minute auto-off) ships only together with the
   logging starter's timed switch.

### 9. Clients Stratum did not build
1. A generated interface joins through `clients.<name>.interface`.
2. A generated client that takes a client gets `stratumHttpClients.restClient("<name>")`.
3. A client with its own engine uses `stratumHttpClients.settings("<name>")` and `.resilience("<name>")`.

Stratum never wraps, replaces or checks a foreign client, and unused blocks are allowed.

### 10. Mocks
`mock: true` answers from `classpath:stratum-mocks/<client>/<method>.json` and logs a WARN at every startup.
`MockClient` in `stratum-test` scripts answers in tests.

## Rule changes

Approved by the owner on 2026-11-09 (ST-AG-13).

1. **ST-ENG-34**, `docs/agents/engineering.md`, add: "One exception: the HTTP client starters keep every setting
   of a client, base URL and timeouts included, under `stratum.http-client.clients.<name>.*`, and the shared
   values under `stratum.http-client.defaults.*` (ADR 0011). Spring Boot's `spring.http.serviceclient.<name>.*`
   keys do not apply to a Stratum client." The spike showed Boot's keys are ignored once Stratum builds the client.
2. **ST-SEC-03**, `docs/agents/security.md`: its last sentence becomes "Two exceptions: the web module logs the
   client's IP address (`clientIp`) for security use; and the HTTP client starters log the called URL with its path
   and query values in the call log line, only after the logging starter's redaction (ADR 0011). No other personal
   data is logged, and the documentation tells teams never to put personal data in a URL." ST-SEC-02 gets "(except
   as ST-SEC-03 allows)" after "personal data".
3. **ST-ENG-51**, `docs/agents/engineering.md`, add: "A write that was never sent (a connect failure), or that
   the server rejected before doing any work (a 401 to an expired service token, retried once), may be retried;
   that is not a retried write (ADR 0011)." AGENTS.md's summary line and `docs/guides/architecture.md` get the
   same short clause. Approved by the owner on 2026-11-09.

## Consequences

- **A team writes an interface and 1 to 4 lines of YAML per client** and gets timeouts, a bounded pool, one retry
  layer, a breaker, tokens, trace headers, metrics and a call log.
- **Every default is safe without configuration (ST-ENG-25):**
  - total 8s, below the inbound 10s;
  - a 2s pool wait;
  - a 5-minute connection lifetime;
  - a 64 KB error-body cap;
  - no network at startup.
- **How the decision weighs (ST-AG-14):**
  - Speed: one pool lease, the interceptors and a breaker check per call.
  - Throughput: each pool is bounded, and a full pool fails fast.
  - Log volume: one WARN per failed call, breaker changes logged once, and body debug only with auto-off.
  - Memory and leaks: the error-body cap, downloads closed through `try-with-resources`, and pools closed with
    the context.
  - Data leaks: redaction on the URL, templates in tags, ID tokens never sent.
  - Security: fixed forbidden headers, redirects off, fail-closed token relay.
  - Maintainability: Spring's own annotations and registries, with no fork.
  - Scalability: the connection lifetime follows pod and DNS changes.
- **Known gaps, accepted:** a `URI` parameter can send auth to another host (owner's decision). The pool wait
  time and the pool-full count are not measured until #237. The ST-SEC-03 exception relies on redaction catching
  data by shape or name. Free text such as `?name=Priya` is logged.
- **PR #79 (H1) must change:**
  - its keys move into the Stratum block;
  - Apache's retry goes off;
  - `max-wait` stays;
  - its `spring.http.serviceclient`-based docs are rewritten.
- **Not verified:**
  - JDK 25 (the spike ran on 21);
  - an HTTPS target through a proxy;
  - a full token exchange;
  - an already-built client picking up a reloaded SSL bundle by itself (the design rebuilds it in Boot's handler);
  - Apache's own retry on 429 (moot with its retry off);
  - the whole reactive stack. The spike covered MVC only; the stale check, lifetime, pool binder and retry-off
    above are Apache HC5's. The reactive starter's own design note checks Reactor Netty's equivalents and the
    Boot 4.1.2 WebClient group bug recorded in PR #79.

### Follow-up items

Each is its own item, with its own track and judge.

- **This ADR and the three rule changes,** in one pull request.
- **H1 rework (PR #79):** the base, the settings block, the pool, retry off and the README, which tells teams never
  to put personal data in a URL (ST-SEC-03).
- **Then, in order:** auth; resilience; errors; the call log and metrics; streaming; mocks and `MockClient`;
  foreign clients; body debug once the logging switch exists.
- **Reactive starter:** the same rules on Reactor Netty.
- **Planned issues:**
  - filed: #236, #237, #238, and #240 (masking query values on HTTP client span URLs; owner, 2026-11-09;
    ST-SEC-03), which the observability work owns;
  - to file: the egress proxy (an HTTPS target through a proxy), a mocked client visible in a metric or in actuator
    info, `afterReceive` in interceptors, breaker and pool state in actuator health, a graceful shutdown test,
    service discovery (Eureka, Consul, Kubernetes), and the JWT-bearer grant.
- **Ready for production only after:** fault tests, a load test, a judge PASS and one pilot service.
