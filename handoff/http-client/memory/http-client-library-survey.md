---
name: http-client-library-survey
description: HTTP client starter design 2026-11-07..09: decisions, files, ADR 0011 status
metadata:
  type: project
---

Files in /mnt/project-files/design/http-client-libraries/:
survey.md (library survey, sections 10-12 design), client-examples.md (one example per client type),
feature-list.md (~70 features, status per row; the current master list).

Decided 2026-11-07 (cards):
- Base: Spring RestClient/WebClient + Resilience4j + Spring Security OAuth2; Stratum's own layer; Spring Cloud = later adapter (Spring Cloud 2025.1 lists Boot 4.0 only).
- No copying other libraries' way of working; no endpoints in YAML. Model: `@StratumClient("name")` interface, scanned like Spring Data repos (08:54Z).
- Two starters kept; Mono/Flux method without reactive starter fails startup (08:55Z).
- LB: direct URL + `lb://`; discovery = planned issues.
- Auth: none, basic, bearer secret, API key, client credentials, token relay, mTLS; token exchange + JWT-bearer planned (08:59Z).
- Resilience4j inside client starters, retry + breaker on by default (09:00Z).
- One `stratum.http-client.clients.<name>` block incl. URL/timeouts (09:03Z) -> needs ST-ENG-34 exception (text in survey 11.2, awaiting go). PR #79 docs/keys need rework.
- Body logging opt-in per client, masked (10:42Z). Debug = log level per client (`stratum.http-client.<name>`, parent = all), live via actuator (10:46Z). 11:20Z: NO line cap; auto-off 15 min default, longer configurable.
- 11:20Z requirement list also: retries up to 9 (Claude's reading) + backoff types, header allow/deny, static + bean-generated headers, tenant ID forward, interceptors (`@StratumClientInterceptor` liked), redirects off, mock clients by config in any scope, Stratum error mapping.

Pool DECIDED 11:24Z: own pool per client by default, pool.shared: true joins shared. Awaiting go: ST-ENG-34 exception, feature list, filing planned issues.
Related: [[http-client-plan]] [[sdk-support-breadth]]
20:18Z: round 2 too simple for Aashish; round 3 -> design-v3.md (Spring @PostExchange, interceptors as beans); superseded by design-v4.md and ADR 0011.

Aashish 11-08 23:37Z (firm): Stratum must integrate with external/third-party generated HTTP clients and never conflict with them. Answer given: never wraps/replaces/checks foreign clients; opt-in A interface: in YAML, B pass restClient(name), C own-engine left alone.
23:44Z: agreed connection lifetime/idle release, streaming, per-client pool metrics; size cap parked -> issue #236 (optional, per endpoint). Open: core pool size (HC5 has no min-idle, Claude belief).
23:48Z: no core pool; max-connections 50 + idle-timeout 30s per client; pool metrics per client (max, busy, idle, waiting) + wait-time timer and pool-full counter (all agreed 23:49Z), Micrometer HC5 binder names.
11-09: gap reviews merged -> gap-review-merged.md: 16 fixes A1-A16 + 5 decisions B1-B5 asked one at a time B1 rejected; A1-A16 + B2-B5 decided (walk-through DONE 00:34Z) 00:09-00:24Z (A11: real url logged masked + uri template; ST-SEC-03 exception text pending) (see gap-review-merged.md Decisions); A12: only 4 binder pool metrics, other 2 -> #237; token-exchange issue #238; spike DONE (spike-result.md, 9/9 work); design-v4.md + adr-0011-draft.md written 11-09; Boot serviceclient key for Stratum client = startup stop; SX-HTTPC-0004 = 500; ADR PR go 00:46Z (+ST-ENG-51 401-resend line 00:48Z); note judged 3 rounds, owner accepted 01:05Z; PR #239 MERGED 01:49Z (ADR 0011 in main). H1 go 01:50Z; note design/http-client-libraries/h1-design-note.md (blocking only, fresh branch, no Boot global timeouts) at judge.
H1 note judge round 1 (2026-11-09 ~02:10Z): FAIL, reviews/judge/H1-design-note.md: F1 API list must include HttpClientProperties/ClientSettings (+builders); F2 decision->test table missing; M1 startup codes should be SX-HTTPC-1001/1002 (all modules use 1xxx for startup stops); M2 thread-hop line. Three scope calls (blocking only, no Boot global timeouts, fresh branch) accepted.
Lane C C1 (streaming) note judge r1 2026-11-09 13:50Z (Fable): FAIL, reviews/judge/C1-streaming-note.md. Fix track confirmed (tests+docs only; H1 already streams both ways, probe reproduced). F1 should-fix: `timeout` is HC5 socket read timeout = gap between bytes on EVERY call (non-streamed 8-byte answer at 200ms gaps read in 1.66s with timeout=500ms); docs row "answer must come within timeout" and ClientSettings Javadoc are false until lane B total-timeout; note must say so. F2 minor: planted fault setMaxConnTotal only survives (per-route limit stays). Upload-buffering test is real: any ClientHttpRequestInterceptor turns 5 MB chunked upload into Content-Length.
