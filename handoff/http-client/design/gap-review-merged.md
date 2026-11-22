# Gap review merged (Opus + Fable, 2026-11-08/09)

Sources: review-opus.md, review-fable.md. Both: feature set is right; gaps are rules and defaults inside agreed
features. Claude checked each against design-v3.md.

## A. Fixes with one obvious answer (added unless Aashish objects; no new keys)

| # | Fix | Found by |
|---|---|---|
| A1 | Apache HttpClient 5's own retry turned off; Resilience4j is the only retry layer (`retries: 3` = at most 4 attempts) | both (blocker) |
| A2 | Wait for a free pool connection fixed at 2s (as PR #79), then the call is refused and counted | both (Opus blocker) |
| A3 | OAuth2 token request gets connect 2s / read 5s timeouts | both (Opus blocker) |
| A4 | Connection time-to-live ~5 min fixed (agreed 23:44Z, lost from keys) | Fable |
| A5 | Stale-connection check after ~2s idle and honour server `Keep-Alive: timeout`; `idle-timeout` stays 30s | both |
| A6 | Breaker counts only timeouts, I/O errors, 5xx, 429; 4xx and 404-as-empty never count; open breaker = own code, 503; state change logged once, refused calls counted, not logged each | both |
| A7 | Retry list: connect failure, I/O error before the answer starts, 502, 503, 504, 429; `Retry-After` honoured within the time left; no retry while breaker open; read timeout never retried on POST | both |
| A8 | `total-timeout` on a blocking call: each attempt's timeout = min(`timeout`, time left) | Opus |
| A9 | Token relay: only the verified access token, never an ID token or a raw header; no incoming token (scheduled job, @Async) = call stops with a clear code; `copy-headers` with nothing to copy sends nothing; token relay without the security starter stops startup | both |
| A10 | Error body read capped at 64 KB (separate from parked #236) | Opus |
| A11 | Call log, spans and metric tags use the URI template (`/orders/{id}`), never the filled URL | Fable |
| A12 | Pool wait time and "refused because full" are Stratum's own meters; client-name tag is Stratum's | Fable |
| A13 | Startup never touches the network; a dead downstream never fails readiness | both |
| A14 | Streaming: a streamed upload is never retried and an interceptor can't read its body; a download holds a connection until closed (docs show try-with-resources); `timeout` on a download = time between bytes | Opus |
| A15 | Client `headers` add to `defaults.headers`, client wins per name | Opus |
| A16 | A short spike proves the not-verified list before the ADR (service groups per client, per-client pools, token relay, client credentials without a user, Micrometer HC5 binder, SSL bundle reload) | Opus |

## B. Decisions for Aashish (one at a time)

| # | Question | Recommendation |
|---|---|---|
| B1 | Stop credentials going to a host not in config (a `URI` method parameter or an absolute URI in an ad-hoc call) | Runtime check: auth and headers only added when host:port match the client's url/urls, else the call stops |
| B2 | Default `total-timeout`: 20s outlasts the web starter's 10s inbound timeout | 8s |
| B3 | Downstream answers 401 on a client-credentials client (token revoked early) | Drop cached token, fetch new one, retry once |
| B4 | Name the token kinds the same as the 11-06 security decision (relay, exchange, service) inside `auth` | `auth: { token-relay }`, `auth: { client-credentials }` = service; `exchange` planned under `auth` |
| B5 | Body debug logging before the logging starter's timed auto-off switch exists | Ships only together with the switch |

## C. Later (planned issues)
Corporate egress proxy (check HC5 honours JVM proxy settings before release); mocked client visible in a metric or
actuator info; interceptor `afterReceive` hook; breaker and pool state in actuator health; graceful shutdown test.

## Decisions
- B1 (gap 1, host check): **closed 2026-11-09 00:08Z, Aashish: rejected.** No check, no documentation. Not a flaw in his view.
- A1 (gap 2, one retry layer): **agreed 00:09Z** - Apache HC5 retry off; Resilience4j only.
- A2 (gap 3, pool wait): **agreed 00:10Z** - key `max-wait`, default 2s, per client and defaults.
- A3 (gap 4, token request timeouts): **agreed 00:11Z** - uses client defaults, counts toward total-timeout, no key.
- B2 (gap 5, total-timeout default): **agreed 00:12Z** - 8s.
- A4 (gap 6, connection lifetime): **agreed 00:15Z** - key `connection-lifetime`, default 5m; checked when a connection returns to or leaves the pool, never during a call.
- A5 (gap 7, stale check): **agreed 00:15Z** - fixed, no key: check a connection idle >2s before reuse, honour server `Keep-Alive: timeout`; `idle-timeout` stays 30s.
- A6 (gap 8, breaker rules): **agreed 00:17Z** - fixed: only timeouts, I/O errors, 5xx, 429 count; 4xx/404-as-empty never; open = SX-HTTPC-0003 / 503; one breaker per client; state change WARN once, refused calls counted. Custom rules via Resilience4j Java customizer.
- A7 (gap 9, retry list): **agreed 00:18Z** - fixed: retry connect/I/O-before-answer, 502, 503, 504, 429; never other 4xx, 500, 501; POST/PATCH only if nothing sent; Retry-After on 429/503 honoured within total-timeout else fail now; no retry while breaker open; Java can override.
- A8 (gap 10, total-timeout on blocking call): **agreed 00:19Z** - fixed: each attempt timeout = min(timeout, time left); no new attempt under ~100ms left.
- A9 (gap 11, token relay): **agreed 00:21Z** - fixed: only verified access token relayed (never ID token/raw header); no caller token = call stops SX-HTTPC-0004; copy-headers with nothing sends nothing; token-relay without security starter stops startup.
- A10 (gap 12, error body cap): **agreed 00:21Z** - fixed 64 KB read into DownstreamException, rest discarded; success answers untouched; #236 stays parked.
- A11 (gap 13, call log address): **changed by Aashish 00:23-00:24Z** - log line carries real `url` (path + query values kept, logging starter's always-on redaction masks emails/cards/token-key params) AND `uri` template; metric tags and span names stay template. Needs a written ST-SEC-03 exception for the client call log (rule text to propose with the ADR); docs tell teams not to put personal data in URLs.
- A12 (gap 14, pool meters): **changed by Aashish 00:25Z** - first release ships only Micrometer binder's 4 (max, busy, idle, waiting); wait-time timer + pool-full counter -> planned issue #237.
- A13 (gap 15, startup/readiness): **agreed 00:27Z** - fixed: no connection/DNS/token fetch at startup; readiness never depends on a downstream.
- A14 (gap 16, streaming): **agreed 00:28Z** - fixed: streamed upload never retried; interceptor body() empty for streams; download holds connection until closed (docs try-with-resources); download timeout = max gap between bytes.
- A15 (gap 17, headers merge): **agreed 00:29Z** - client headers add to defaults.headers, client wins per name, empty value drops a default; copy-headers lists joined.
- B3 (gap 18, 401 on client-credentials): **agreed 00:30Z** - drop cached token, fetch new, retry once (POST too); second 401 fails, logged once per client per minute; token-relay 401 passed back unchanged.
- B4 (gap 19, token kind names): **agreed 00:32Z** - one `auth` key: token-relay (=relay), client-credentials (=service), token-exchange (=exchange, planned issue #238); auth replaces 11-06 example key `token`; 11-06 rules unchanged.
- B5 (gap 20, body debug timing): **agreed 00:34Z** - body logging ships only together with the logging starter's timed auto-off switch; client starter ships first without it.
- A16 (gap 21, spike): **agreed 00:34Z** - throwaway spike outside repo checks 9 items; results in spike-result.md; then design update + ADR.
- ADR open 1 (Boot serviceclient key for a Stratum client): **agreed 00:45Z** - startup stops, naming the Stratum key.
- ADR open 2 (SX-HTTPC-0004 status): **agreed 00:46Z** - 500.
- ST-ENG-51 line (401 resend, POST incl.): **agreed 00:48Z** - added to the ADR PR.
- Spans (from ADR note round 1): **decided 00:54Z** - observability starter masks query values on HTTP client span URLs (host + path kept); separate observability item via coordinator; ADR 8.2 gets a pointer after round-2 verdict.
- Call log without logging starter: **decided 01:01Z** - uri template only; docs say so. Owner approved a final (round 3) note judge.
