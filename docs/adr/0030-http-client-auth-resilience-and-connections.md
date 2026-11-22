# ADR 0030: HTTP client auth, retries, circuit breaker and connections

**Status:** Accepted, 2026-11-22, by the owner. Its decisions were taken from 2026-11-06 to 2026-11-20; each
gives its date.

One of four records on the HTTP client starters. The keys and their defaults are in
[ADR 0029](0029-http-client-starters-and-settings.md); this record owns what auth, retries, the breaker and the pool
do with them.

[← Documentation home](../README.md) · How to use it: [Auth and mTLS](../modules/http-client-auth.md) ·
[Retries and the circuit breaker](../modules/http-client-resilience.md)

## Context

- **The security decision of 2026-11-06** named three kinds of token for outgoing calls: "relay" (the caller's),
  "service" (the service's own) and "exchange" (RFC 8693).
- **Apache HttpClient 5 retries on its own,** a 503 included (the spike of 2026-11-09). Two retry layers multiply
  attempts.
- **Retry inside a `RestClient`** is possible only with a request interceptor, which makes Spring hold the whole
  request body in memory (spring-web 7.0.10, `InterceptingClientHttpRequest`). A request factory whose request runs
  each attempt as a fresh request of the client's own factory retries and guards the call while the body streams
  once (the design note of issue [#261](https://github.com/exepex/stratum/issues/261)).
- **Every client's retry and breaker are set per client and under `defaults`,** with safe values when nothing is set
  and a team free to change them (owner, 2026-11-09).

## Decision

### 1. Auth (owner, 2026-11-09)

1. `auth` holds exactly one kind:
   - `token-relay: true`, the caller's token;
   - `client-credentials: <registration>`, the service's own token;
   - `api-key` (with optional `api-key-header`);
   - `bearer`;
   - `basic`.

   mTLS goes through `ssl-bundle`. `token-exchange: <registration>` is planned under the same block
   ([#238](https://github.com/exepex/stratum/issues/238)), and the security starter's on-behalf-of work depends on it.
2. Token relay sends only an access token that Spring Security's OAuth2 resource server verified, never an ID token
   or a raw header. Without the resource server (`spring-boot-starter-oauth2-resource-server`), `auth.token-relay`
   stops startup with `SX-HTTPC-1006`. With no caller token, the call stops before sending
   with `SX-HTTPC-0004`.
3. Client credentials: on a 401, the cached token is dropped, a new one is fetched, and the call is retried once,
   POST included. A second 401 fails the call and is logged once per client per minute. The token request uses the
   client's timeouts and counts toward `total-timeout`. With token relay, a 401 is not sent again, since only the
   caller can get a new token; it is thrown as [ADR 0031](0031-http-client-errors-and-body-limits.md) says.
4. A user's identity travels only inside a signed token. `Authorization`, `Cookie`, `Set-Cookie`,
   `Proxy-Authorization` and the API-key header are never accepted in `headers` or `copy-headers`. Secrets come only
   from `${...}` placeholders.
5. Stratum does not check the target host of a `URI` method parameter or an absolute ad-hoc URI, and its
   documentation adds nothing about it: the client's configuration and code belong to the team. ST-SEC-17 is a rule
   for the service's own code, so it is unchanged.

### 2. Retries and the circuit breaker

1. **One retry layer** (owner, 2026-11-07). Resilience4j, inside the client starters, is the only retry layer.
   Apache HttpClient 5's own retry is off, so `retry.max-retries: 3` means at most 4 attempts. The client starters do
   not depend on `stratum-starter-resilience`, and its switch does not change them (ST-ENG-10, ST-ENG-11). When the
   shared Resilience4j setup moves into a library, both use it
   ([ADR 0024](0024-bundles-parents-and-module-switches.md), decision 2).
2. **What is retried** (owner, 2026-11-09). A connect failure is retried for every method: nothing was written. A
   dropped connection, a read timeout, `502`, `503`, `504` and `429` are retried only for `GET`, `HEAD`, `OPTIONS`,
   `PUT` and `DELETE`, the idempotent methods of RFC 9110. Other 4xx, `500` and `501` are never retried. `POST`,
   `PATCH` and any other method are retried only after a connect failure (ST-ENG-51).
3. `Retry-After` on 429 or 503 is honoured within the time left. There is no retry while the breaker is open, and a
   streamed upload is never retried.
4. Each attempt's connection wait, connect timeout and `read-timeout` are cut to the time left under
   `total-timeout`. No new attempt starts with less than about 100ms left. The default of 8s stays below the web
   starter's 10s answer timeout.
5. There is one breaker per client. **Counted as failure:** timeouts, I/O errors, 5xx and 429. **Never counted:**
   other 4xx and a 404 read as empty. When open, the call stops with `SX-HTTPC-0003`. A state change is logged once,
   and refused calls are counted.
6. The keys set how often and how long to wait and when the breaker opens (owner, 2026-11-09). What stays fixed:
   which failures are retried and which the breaker counts, a `POST` never sent again once written, and one breaker
   per client. A `StratumClientCustomizer` runs after the keys, for what they cannot express.
7. **A client's beans retry and guard their calls too** (owner, 2026-11-10). A call through the `RestClient` or
   `RestTemplate` bean of [ADR 0029](0029-http-client-starters-and-settings.md) decision 3.2 uses the client's
   settings and its one breaker, shared with its interfaces: a call without a body is retried under decision 2.2; a
   call with a body is sent once; an open breaker refuses a call with `SX-HTTPC-0003`. Each attempt waits for the
   answer at most `read-timeout` or the time left under `total-timeout`, so the whole call has its answer by
   `total-timeout`. The credential runs on each attempt's request, so a service token is fetched within the attempt
   and renewed after a 401 for a call without a body.
8. **Where they belong** (owner, 2026-11-20). A call's timeouts belong to the HTTP client starter. Resilience is
   about when to handle error situations, such as when the circuit breaker opens and closes and at what error rate.
   Whether and how that part moves out of the client starters is parked until the resilience work resumes; until a
   later record decides it, decisions 2.1 to 2.7 stand.

### 3. Connections (owner, 2026-11-09)

1. Each client has its own pool (`shared-pool: true` joins one). After `max-wait`, the call is refused.
2. Idle connections close after `idle-timeout`. A connection idle for more than about 2s is checked before reuse.
   The server's `Keep-Alive: timeout` is honoured.
3. `connection-lifetime` is checked only when a connection returns to or leaves the pool, never during a call.
4. Redirects are never followed. Values are always encoded. Gzip is accepted.
5. Startup does no network work, and readiness never depends on a downstream.

## Consequences

- Every default is safe without configuration (ST-ENG-25): total 8s, below the inbound 10s; a 2s pool wait; a
  5-minute connection lifetime; no network at startup.
- A retried call through a bean is one span and one `http.client.requests` metric, with the last answer; an interface
  records one per attempt. A body cut off after the answer's head is neither retried nor counted for a bean. An
  answer read on a late attempt has only that attempt's time left between bytes; a team that downloads large files
  through a bean raises `total-timeout`.
- **Known gap, accepted:** a `URI` parameter can send auth to another host (decision 1.5).
- How the decision weighs (ST-AG-14):
  - Speed: one pool lease, the interceptors and a breaker check per call; one extra request object per attempt for a
    bean; the retry and breaker keys are read once, when a client's retry and breaker are built.
  - Throughput: each pool is bounded, and a full pool fails fast; an error answer's connection is freed before any
    wait.
  - Log volume: one `WARN` per change of a breaker's state.
  - Memory and leaks: a count-based window holds one entry per attempt, capped at 10000 per client; pools close with
    the context.
  - Data leaks and security: fixed forbidden headers, redirects off, fail-closed token relay, ID tokens never sent;
    startup errors name the rule, never the value.
  - Maintainability and scalability: Spring's own annotations and registries, stable Resilience4j APIs, state per
    client only, and one noisy client can open early without making the others stricter.
- Rule ST-ENG-51 carries decision 2.2 and the single 401 retry of decision 1.3: a write never sent, or rejected
  before any work, may be retried (owner, 2026-11-09).
