# Independent review: MVC HTTP client design, gaps before "production grade" (Fable, 2026-11-09)

Read: survey.md 10-12, design-v3.md (with the 11-08 decisions), client-examples.md, feature-list.md, the design
thread of 11-07/08, the 11-06 outbound-identity decision (security-pentest/result.md), PR #79's code on branch
`claude/project-thread-ups8xn`. Decided and parked items are not re-raised. Library facts marked "believed" were
not checked in the jar.

Verdict: the feature set is right for an MVC client. Nothing below adds a feature a team would see. Each gap is a
default, a rule or a wording the design must fix so the client behaves safely at volume. Two are blockers.

## Blockers

**1. A method with a `URI` parameter bypasses the configured host (SSRF, token leak).**
Spring HTTP interfaces accept a `URI` or `UriBuilderFactory` argument that replaces the base URL. With
`token-relay`, the caller's token then goes wherever that argument points. The 11-06 decision (M-50) says relay
only to the client's configured host. Rule: Stratum refuses, at client creation, a `@StratumClient` or
`interface:` method whose parameter overrides the URL. This checks Stratum's own feature, not the team's code.
Example: `Order follow(URI link)` with a link from a vendor's response body sends `Authorization` to that host.

**2. Two retry layers.** PR #79 keeps Apache HttpClient 5's own retry of idempotent calls on a dropped connection
(`RestClientRetries`) and version 3 adds Resilience4j retries on top. One failed GET can then be sent
(1 + Apache retries) × (1 + `retries`) times, and `total-timeout` is the only cap. Rule: when Resilience4j owns
retries, Apache's retry strategy is off (`DefaultHttpRequestRetryStrategy` replaced by never-retry), so `retries: 3`
means at most 4 attempts, on both stacks. Example: `retries: 3` and Apache's default of 1 gives up to 8 attempts
during a downstream outage, doubling the storm.

## Should fix in the design (before the ADR)

**3. Default deadlines do not fit together.** The web starter answers our caller with `503` after 10s (standard
mode). The client's `total-timeout` default is 20s, so after our caller has already got a `503`, the thread keeps
retrying for 10 more seconds and holds a pool connection. Under load that is thread exhaustion. Proposal: default
`total-timeout` below the web response timeout (for example 8s), or state in the ADR why 20s is right and that a
team lowers it. Example: inventory hangs; 200 requests/s each hold a thread 20s instead of 10s.

**4. Connection time-to-live was agreed, then dropped from the keys.** 23:44Z agreed "connection lifetime", 23:48Z
kept only `max-connections` and `idle-timeout`. Under constant load a connection never goes idle, so a pod behind a
Kubernetes service IP keeps all traffic while new pods get none, and a DNS change is never seen. Fixed default, no
key: close a connection after about 5 minutes (HC5 `setConnectionTimeToLive`, believed; Reactor Netty `maxLifeTime`).

**5. `idle-timeout` 30s must be shorter than the downstream's keep-alive.** If the server closes an idle connection
first, the client's next call on it fails with a reset. Embedded Tomcat's default keep-alive is about 20s (believed,
not verified). Either default `idle-timeout` to a value below that (for example 15s), or rely on HC5's stale check
after inactivity and say so in the ADR with a test (idle 25s, then call, no error).

**6. The pool wait limit is missing.** PR #79 has `pool.max-wait` (2s); version 3 has no wait key and no fixed
value. HC5's default wait for a free connection is 3 minutes (believed). The agreed metric "calls refused because
the pool was full" needs that limit. Proposal: fixed wait = `connect-timeout`, no new key; or keep `max-wait` from #79.

**7. What opens the circuit breaker.** Not defined. If `404` and other `4xx` count as failures, a burst of
`findOrder` lookups that return `Optional.empty()` opens the breaker on a healthy service. Rule: failures are connect
and read timeouts, I/O errors, `5xx` and `429`; `4xx` is the caller's error and never counts. State the error a
caller gets when the breaker is open (SX-HTTPC-0002, `503`?) and that the breaker is per client, with per-address
skipping for `urls` as a separate short block list.

**8. Retry conditions and `Retry-After`.** "Brief failures" is not a list. Rule: retry on connect failure, I/O
error before the response starts, `502`, `503`, `504`, and `429`; honour `Retry-After` on `429`/`503` within
`total-timeout`; never retry a read timeout on a non-idempotent method. Feature list 4.4 had `Retry-After`; version
3 lost it.

**9. Token relay with no incoming token must fail closed and never relay an ID token.** A `@Scheduled` job or an
`@Async` thread has no security context. Rule: a token-relay client called without a validated access token in the
context fails the call with a clear SX code before sending; it never sends without `Authorization` or with a
token taken from elsewhere. Relay sends the incoming *access* token only; the 11-06 rule "ID tokens are never sent
to other services" belongs in the design text.

**10. Reconcile the two token designs.** 11-06 decided three modes per client, `relay | exchange | service`, under
`clients.<name>.token`. Version 3 has `auth: { token-relay | client-credentials }` and puts exchange in the planned
issues. The ADR must say that version 3's `auth` is the one key, that `exchange` is the planned third kind under the
same `auth` block, and that the security starter's on-behalf-of work depends on it, so the issue is not lost.

**11. The token endpoint call has no limits.** For `client-credentials`, Spring Security fetches and caches the
token with its own HTTP client. State that this call gets a connect and read timeout (Stratum's defaults) and is
counted in `total-timeout`, and what happens on a `401` from the downstream after a secret rotation (refresh once
and retry, or fail and log). Example: the SSO token endpoint hangs; without a timeout every `invoices` call hangs
with it, and the breaker never opens because no error returns.

**12. Call log and spans must use the URI template, never the expanded URL.** `GET /users?email=a@b.com` logged as
a failed call at WARN is personal data in logs (ST-SEC-03), and an expanded path as a metric tag is unbounded
cardinality (OBS-7 caps). Rule: log and tag `client`, method, URI template (`/orders/{id}`), status, outcome.

**13. Two of the six pool metrics need Stratum's own code.** Micrometer's HC5 pool binder (believed:
`PoolingHttpClientConnectionManagerMetricsBinder`) gives max, leased, available and pending, tagged by manager, not
by client. Wait time and "refused because full" are not in it. Say in the design that the client-name tag and
these two meters are Stratum's own instrumentation, so the implementer does not promise what the binder lacks.

**14. Startup never touches the network.** State it: a client bean is created lazily, no connection, DNS lookup
or token fetch happens at startup, and a dead downstream never fails readiness. Teams will otherwise ask.

**15. Body debug depends on the logging starter's timed switch, which is not built.** Rule for the order of work:
body logging ships only after the auto-off switch exists, or ships off with no way to turn it on. Debug without
auto-off on a busy service is a log-volume incident.

## Later (planned issues, not blocking)

- **Response hook in interceptors.** `beforeSend` only. Reading a vendor's rate-limit headers or verifying a
  response signature needs `afterReceive`. Cheap to add to the same interface later.
- **Egress proxy for third parties.** Already a planned issue. Verify before release that an HC5 client built by
  Boot honours `-Dhttps.proxyHost`; if not, a team behind a corporate proxy cannot reach any vendor and the issue
  moves up.
- **Breaker and pool state in actuator health.** Resilience4j ships health indicators; wire later if operators ask.
- **Graceful shutdown.** Pools and token managers close after in-flight calls finish; Boot does most of it, test it.

## Checked and fine

Streaming, pool metrics per client, idle timeout, max connections, forbidden headers, redirects off, encoding,
foreign client cases A/B/C, mock WARN, `DownstreamException` with capped body, `Optional` = 404, Java customizer
over YAML, startup checks limited to Stratum's own blocks. `stratum-starter-resilience` and
`stratum-starter-oauth2-client` in the repo are empty POMs today, so no duplication yet; the client starters should
depend on them once they hold the shared Resilience4j and OAuth2 beans, not copy them.
