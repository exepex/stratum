---
name: http-client-plan
description: HTTP client plan, all 6 questions answered by Aashish 2026-10-31 (thread "Plan the HTTP client module"); PRs H1-H4; H1 = PR #79 (opened 2026-11-01)
metadata:
  type: project
  modified: 2026-10-31T23:42:09.142Z
---

Boot 4.1.2 facts (from jar metadata): spring.http.clients.{connect-timeout,read-timeout,redirects,ssl,imperative.factory,reactive.connector}; per group spring.http.serviceclient.<group>.{base-url,default-header,apiversion,connect-timeout,read-timeout,redirects,ssl}. Boot sets NO timeouts and has NO pool keys.

Decisions (Aashish, 2026-10-31):
1. Spring's serviceclient keys for what Boot has; stratum.http-client.* only for extras. README: one full copy-paste example (Spring + Stratum keys) + table. Renames: spring-boot-properties-migrator as-is; deprecated Stratum key kept >=1 release with replacement; release notes list renames. Test fails if a documented Spring key is removed/deprecated.
2. Apache HttpClient 5 under RestClient, Reactor Netty under WebClient; bounded configurable pools + pool metrics.
3. X-Correlation-Id always out; other headers only via forward-headers (shared, per-client replaces); Authorization/Cookie/Set-Cookie/Proxy-Authorization/X-Api-Key never, listing one fails startup.
4. DownstreamUnavailableException: SX-HTTPC-0001 timeout->504, -0002 connect->503, via stratum.web.error.statuses; 4xx/5xx stay Spring's.
5. stratum.http-client.call-log=none|errors|all, default errors (one WARN per failed call); never headers/bodies.
6. Two starters: stratum-starter-http-client (blocking) + stratum-starter-reactive-http-client, shared libs/stratum-http-client; reactive bundle -> reactive starter.

7. (2026-10-31, during H1) Pool gauges on reactive only (Reactor Netty metrics); Boot never exposes Apache HC5's pool, so blocking gets none for now, README says so.
Defaults: spring.http.clients.connect-timeout 2s / read-timeout 5s via StratumDefaults (ST-ENG-34: no duplicate stratum key); stratum.http-client.pool.max-connections 50, pool.max-wait 2s.
H1 branch: claude/project-thread-ups8xn.
PRs: H1 base/settings/pool/bundles/README, H2 headers, H3 errors (touches stratum-web; W6 merged), H4 call log. Aashish said go 2026-10-31. H1 = PR #79, opened 2026-11-01, awaiting judge.

Related: [[every-setting-java-and-ide]], [[stratum-web-plan]].

Boot 4.1.2 bug (found during H1): WebClient HTTP interface groups lose their own spring.http.serviceclient.<name>.* timeouts/SSL (WebClientCustomizerHttpServiceGroupConfigurer, order 0, re-applies the shared connector after the properties configurer). Blocking groups and the global keys are fine. Workaround WebClientGroupSettings (order 1) is in PR #79, with SpringBootGroupTimeoutBugIntegrationTest failing once Boot fixes it. Aashish chose A (workaround) on 2026-11-01. After Codex P1 it rebuilds only groups that set timeout/redirects/cookie-handling/ssl.bundle; a WebClientCustomizer connector on such a group is replaced (docs say use ClientHttpConnectorBuilderCustomizer).
Migrator check: the 4.1.2 properties migrator reads classpath*:/META-INF/spring-configuration-metadata.json, which Stratum jars ship (checked by bytecode, not run end to end).
H3 error statuses (Aashish, 2026-11-01, option A): libs/stratum-http-client owns DownstreamUnavailableException, SX-HTTPC-0001/0002 and their 504/503 defaults, adding them to stratum.web.error.statuses at lowest priority (StratumDefaults) so the web module needs no change. Aashish wants HTTP client config kept in the HTTP client modules, not web.
Stratum's builder customizers use HttpClientConstants.CUSTOMIZER_ORDER=1 so a service's unordered customizer wins (judge round 1, PR #79).

SUPERSEDED IN PART 2026-11-07 (Aashish): decision 1 (Spring serviceclient keys) replaced by one stratum.http-client.clients.<name> block; client model is now @StratumClient interfaces. See [[http-client-library-survey]].
