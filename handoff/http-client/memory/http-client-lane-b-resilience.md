---
name: http-client-lane-b-resilience
description: HTTP client lane B step 1 (B1 resilience) design note judges r1 (Fable) and r2 (Opus) 2026-11-09: both FAIL on text fixes; Resilience4j stop-signal trap
metadata:
  type: project
  modified: 2026-11-09T13:38:59.318Z
---

B1 = retries, retry-delay, total-timeout, circuit breaker for the blocking HTTP client starter (ADR 0011 4.1-4.5).
Note: design/http-client-libraries/b1-resilience-design-note.md (revised 13:40Z). Verdict round 1 (Fable 5.1, 2026-11-09
~13:45Z): FAIL, reviews/judge/B1-resilience-note.md.

Design facts verified in Spring Web 7.0.10 / HttpClient 5.6.5 / Resilience4j 2.4.0 sources (all hold):
- Retry wraps CircuitBreaker wraps the whole exchange as an `HttpExchangeAdapterDecorator` set via
  `Groups.forEachProxyFactory`; `HttpServiceProxyFactory.Builder.exchangeAdapterDecorator` composes with `andThen`.
  No `ClientHttpRequestInterceptor`: `InterceptingClientHttpRequest extends AbstractBufferingClientHttpRequest`
  (buffers every body, breaks streamed uploads). `ClientExchangeDecorator` (internal, Ordered) is the one composition
  point for all lanes: retry, breaker, lane A 401 resend, lane C mock.
- Per-attempt timeouts via `setHttpContextFactory` + `ScopedValue`: a custom `RequestConfig` in the context wins in
  Spring; HC5 takes connectTimeout from the context config when set (deprecated setter, still honoured), else
  `ConnectionConfig`. `responseTimeout` is one socket timeout for headers and body.
- Resilience4j `RetryImpl` decides retry, then sleeps `intervalBiFunction`, then attempts; breaker defaults 50%/100
  calls/min 100/60s open/10 half-open.

Findings: F1 back-off wait must be capped by time left (else call ends up to 2s past total-timeout); F2 three docs
pages still say retries planned (reference.md, installation.md, quick-start.md); M1 streamed-download wording;
M2 HEAD/OPTIONS read-timeout retry not in ADR 4.2. Round 2 needs a fresh judge on another model (Opus).

Round 2 (Opus 5.5, reviews/judge/B1-resilience-note-r2.md): FAIL. r1 F2/M1/M2 fixed; new F1: note never says how
Resilience4j stops when the wait/Retry-After exceeds time left. Probe on 2.4.0: a negative interval rethrows the last
exception on the exception path, but on the retryOnResult path throws IllegalStateException("Unexpected exception
during retry wait interval") instead of the 503. Keep failures as exceptions or decide in the predicate; needs an
integration test. F2: README.md:269 still says retries planned. Minor: deny-list of methods (list GET/HEAD/OPTIONS/
PUT/DELETE instead); answer 1 is 4 lines. Jitter cap verified (applied after jitter, max 2s). After 2 rounds the owner decides.

**Why:** the lane B implementer and the round-2/code judge need the verified facts and open findings.
**How to apply:** check the note was fixed for F1/F2 before starting code; code judge checks the Notes list.
Related: [[http-client-h1-pr]] [[http-client-library-survey]]
