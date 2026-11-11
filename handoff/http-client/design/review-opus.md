# HTTP client starter (MVC): independent gap review (Opus, 2026-11-08)

Read: design-v3.md (with the 11-08 decisions at its end), survey.md sections 10-12, feature-list.md,
client-examples.md, the design thread up to 23:50Z, security-starter-plan.md, docs/agents/security.md, and PR #79's
code (branch `claude/project-thread-ups8xn`, head 0978a0a). Decided and parked items are not re-raised. Facts about
Spring and Apache HttpClient 5 (HC5) marked "not verified" come from memory, not from a run.

Verdict: the feature set is nearly complete. 3 blockers and 9 "should" items, mostly unwritten rules inside features
that are already agreed, not new features. None adds a key except where noted.

## Blockers

**B1. Credentials can go to a host that is not in config.** Spring HTTP interfaces accept a `URI` method parameter
that replaces the base URL, and an ad-hoc `restClient("orders").get().uri("https://other.example/x")` with an absolute
URI does the same (Spring behaviour, not verified on Boot 4.1). Stratum's auth step would still add the token or API
key. This breaks ST-SEC-17 and leaks a user's relayed token.
Fix: auth, `headers` and `copy-headers` are added only when the request's host and port match the client's `url` /
`urls`; otherwise the call stops with a clear error. Cheap runtime check, no startup scan.
Example: `findReport(URI.create(userInput))` on the `partner` client must not send `Bearer ${PARTNER_TOKEN}`.

**B2. Two remote waits have no timeout in the design.** (a) The OAuth2 token request for `client-credentials` goes
through Spring Security's own token client, not the client's pool, so it gets none of Stratum's timeouts (Spring's
default client, not verified). A hanging SSO then hangs every call of every client using it. (b) v3 dropped PR #79's
`pool.max-wait`; HC5's default wait for a free connection is 3 minutes (not verified). Fix: token client gets
connect 2s / timeout 5s fixed; pool wait fixed at a small value (PR #79 used 2s) and counted by the "refused because
the pool was full" metric. Project rule: timeouts on every remote call.

**B3. Two retry layers.** PR #79's `RestClientRetries` keeps HC5's own retry for I/O errors (it only turns off retry
on 429/503 answers). With Resilience4j retries (3) on top, one call can make up to 8 attempts (HC5 default 1 retry,
not verified). Fix: turn HC5's automatic retries off completely; Resilience4j is the only retry layer.

## Should (before the ADR)

**S1. Default timeouts outlast the caller.** Defaults allow 20s per outbound call (`total-timeout`), but the web
starter answers our own caller with 503 after 10s (standard mode). The service keeps working on a request nobody waits
for. Fix: default `total-timeout` below the inbound timeout (for example 8s), and say so in the docs.

**S2. Say how `total-timeout` works on a blocking call.** Resilience4j's TimeLimiter needs a future; a blocking call
cannot be cancelled. Fix in the design: each attempt's timeout = min(`timeout`, time left); no retry when the delay
or a `Retry-After` is longer than the time left; no retry while the breaker is open.

**S3. Stale keep-alive connections.** Many servers close idle connections first (Node.js defaults to 5s, many load
balancers 60s, not verified). With `idle-timeout: 30s`, a reused connection the server already closed fails; on a POST
it is not retried, so random 502s appear. Fix (fixed, no key): check a connection idle for over ~2s before reuse
(HC5 `validateAfterInactivity`), and honour the server's `Keep-Alive: timeout=`.

**S4. Circuit breaker rules are not written.** Fix: only timeouts, connect errors and 5xx count as failures; 4xx
(and 404 on `Optional`) never trip it. A call refused by an open breaker gets its own code mapped to 503. Log volume:
during an outage, one WARN per failed call (H4) floods logs; calls refused by an open breaker are counted in a
metric and the breaker's state change is logged once, not each call.

**S5. Token relay rules (from the security decisions).** (a) Relay only the access token of the verified
`Authentication`, never an OIDC ID token or a raw incoming header. (b) No incoming request (scheduled job, `@Async`
without context, message listener): the call stops with a clear error, never goes out without a token. Same for
`copy-headers`: nothing to copy, nothing sent. (c) `token-relay` with no security starter stops startup (Stratum's own
config). (d) Align names with the security plan's per-client modes `relay | exchange | service`; `exchange` is a
planned issue, and the two-token variant (feature-list 13.3, `X-User-Token`) is allowed only as a signed token the
receiver verifies, never a plain identity header.

**S6. 401 from downstream with `client-credentials`.** A cached token revoked early gives 401 until it expires.
Fix: on 401, drop the cached token, fetch a new one and retry once (any method: the request was refused).

**S7. Streaming rules.** (a) A streamed upload cannot be replayed: never retried, and an interceptor cannot read
`request.body()` for it (say so; signing needs a buffered body). (b) A streamed download holds a pooled connection
until closed; the docs show try-with-resources, and pool metrics show the leak. (c) `timeout` on a download means
time between bytes, not the whole transfer, so big files are not cut off; `total-timeout` covers the call until the
answer starts.

**S8. Error body cap is still needed.** Response size cap is parked (#236), but `DownstreamException` reads the
error body. Fix: read at most a small fixed amount (for example 64 KB) of an error body, always.

**S9. Close the "Not verified" list before the ADR.** v3 lists five unverified items (per-client service groups via
`AbstractHttpServiceRegistrar`, per-client HC5 pools with Spring's group settings, token relay, client credentials
without a user, the timed DEBUG switch). Add: Micrometer's HC5 pool binder names; SSL bundle `reload-on-update`
reaching per-client pools (mTLS certs rotate). A short spike proves them; the ADR should not fix a design on them
unproven. The ST-ENG-34 exception text (survey 11.2) also still needs Aashish's go.

## Later (issues, not first release)

- **L1. Corporate egress proxy.** HC5 ignores the JVM's proxy settings unless told to (not verified); third-party
  calls fail in companies that force a proxy. The planned "per-client proxy" issue covers it; consider moving it up.
- **L2. Mock visible at runtime.** Besides the startup WARN, show mocked clients in a metric tag or actuator info, so
  a mocked `payments` in production is seen on a dashboard.
- **L3. `headers` merge.** Say whether a client's `headers` adds to or replaces `defaults.headers` (suggest: adds,
  client wins per name).
- **L4. No downstream in readiness.** State it: a downstream being down never makes our pod not ready (avoids
  cascading restarts).
- **L5. Ad-hoc URI tags.** `uri("/orders/" + id)` makes one metric tag per ID; OBS-7 caps it, docs show the template
  form `uri("/orders/{id}", id)`.

Nothing here changes a service's contract or adds a startup scan of team code.
