---
name: http-client-download-close-drains
description: Spring 7.0.10 HC5 response close() reads the rest of a streamed download first; PR #253 C1 judge r1 FAIL on the missing doc line, r2 PASS at da48d40
metadata:
  type: project
  modified: 2026-11-09T15:35:30.126Z
---

Fact (javap + probe, 2026-11-09): `HttpComponentsClientHttpResponse.close()` calls `EntityUtils.consume(entity)`
before `response.close()`. Closing an `InputStream` download early blocks until the rest of the body arrives
(each gap bounded by `timeout`; a stall ends the close with `SocketTimeoutException`). After that exception the
pool connection is still given back (r2 probe: next call on a `max-connections: 1` pool succeeded).

PR #253 (C1 streaming) code judge r1 at 8593836 (Opus): FAIL, one should-fix = add that sentence to the Streaming
section of docs/modules/http-client.md. Verdict: reviews/judge/PR-253-http-client-c1.md.
Code judge r2 at da48d40 (Fable): **PASS**, no findings, 2/2 plants caught, module verify exit 0, posted as a
COMMENT review on the PR. Verdict: reviews/judge/PR-253-http-client-c1-r2.md. Note for lane B: the page says
`total-timeout` bounds the whole call, but B1 covers a streamed download only until its headers; B1's docs must say so.
Related: [[http-client-lane-c]] [[judge-build-environment]].
