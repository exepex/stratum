---
name: http-client-lane-a
description: HTTP client lane A (auth, mTLS, then headers/interceptors), thread cmsg_0183M4FLDkiYRAp7MyRDM5gd9oY5AvNxVBP9uCNjA9NM7p: state, design choices, lessons
metadata:
  type: project
  modified: 2026-11-09T21:35:00.000Z
---

Branch claude/project-thread-8ivbzo. A1a merged as PR #251 (16:12Z). A1b = PR #257: judge gate failed on conflicts after #258;
squashed+rebased; judge r1 FAIL at 1bb3b01 (servlet startup); fixes at 62e8f5d; r2 (Sonnet) gate FAIL (conflict with #265); rebased on c8b6d6a, Codex P1 fix 85c14ef; r3 (Fable) PASS at 85c14ef 2026-11-09 21:35Z (6 plants, 5 caught; verdict reviews/judge/PR-257-http-client-a1b-r3.md). MERGED 2026-11-09 21:38Z. A2 ON HOLD (Aashish 19:46Z):
finish/merge in-flight work first; A2 goes into the handover as an issue unless he says otherwise. Token fetched in ServiceTokenResend
before the attempt (Spring creates request + timeout snapshot before initializers) and bound to the attempt
(SingleFetchTokenManager.sending) so short-lived tokens are not refetched. After #258 a 401 surfaces as DownstreamException.

Notes: design/http-client-libraries/a1-auth-design-note.md (PASS r2), a2-headers-interceptors-design-note.md (PASS r2).
Owner (15:19Z): no auth/ssl-bundle in defaults; token fetch fail = SX-HTTPC-0002 503; relay needs resource server;
one in-flight token fetch. Codes lane A: 0002, 0004, 1005, 1006; statuses via stratum.web.error.statuses defaults.

A1b design facts:
- oauth2-client starter: LoginChainImportFilter drops Boot OAuth2ClientWebSecurityAutoConfiguration unless
  spring-boot-security present (spring-security-config alone made servlet apps fail: no HttpSecurity).
- Auth via ClientHttpRequestInitializer (interceptors buffer the body).
- ServiceTokenResend runs INSIDE ClientResilience.call per attempt (lane C ask), not a decorator: deviation from note.
- spring-boot-security-oauth2-client -> exclude spring-boot-security (lock-down) but add spring-security-config
  (CommonOAuth2Provider, else NoClassDefFoundError).
- Token RestClient needs Form + OAuth2AccessTokenResponse converters + OAuth2ErrorResponseErrorHandler.
- Token pool "token:<client>", 2 connections, no ssl-bundle. Expiry skew 60s.

A2 code-judge notes: getHeaders() final on Spring base request -> wrapper implements interfaces itself + getBody();
servlet types behind non-null check; dedupe bean that is both global and named; async controllers copy nothing.
Wrapper must expose delegate attributes (lane C mock reads "stratum.mock.method"). B3 owns trace headers.
Spring Groups.filter resets after each forEach: call filter again before each forEachClient/forEachProxyFactory.

Lessons (Codex findings on #251): request ITs use @SpringBootTest (+ @Nested with own @DynamicPropertySource), never
SpringApplicationBuilder (ST-TEST-31); no private static helpers in tests (ST-ENG-47); @Nested per behaviour even for
small classes (ST-TEST-17); Checkstyle bans "OAuth" abbreviations in names (use Token...); HttpServer tests bind
127.0.0.1 (localhost refused on CI runner).
Related: [[http-client-lane-c]] [[stratum-agent-rules]]
