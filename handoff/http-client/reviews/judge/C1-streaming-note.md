# Judge verdict: C1 streaming design note (lane C, HTTP client starter), round 1

**Item:** design note `design/http-client-libraries/c1-streaming/c1-design-note.md`, base `main` at `aabdae8`
(H1, PR #242). Track proposed: Fix. Review step: design note.

**Verdict: FAIL**, on one should-fix and one minor finding, both fixed by editing the note (no code exists yet).
Everything else in the note holds. Judge model: Claude (Fable); not the model of the H1 code judges' last round
(Opus).

I read `AGENTS.md` and all six topic files (engineering, testing, security, documentation, review, judge) in full,
plus `handoff/stratum-judge-process.md`, ADR 0011 (sections 4, 5, 7 and the follow-up items), gap review A14 and
the H1 code (`ClientConnectionPools`, `StratumClientGroups`, `ClientSettings`, `ClientTimeoutIntegrationTest`,
`ScriptedDownstream`, `docs/modules/http-client.md`).

## What I ran

- JDK 25 (`apt-get install openjdk-25-jdk-headless`), `./mvnw -pl starters/stratum-starter-http-client -am install
  -DskipTests` → exit 0.
- The probe `ProbeStreamingIntegrationTest.java.txt` copied into the starter's `it` package and run with
  `./mvnw -pl starters/stratum-starter-http-client verify -Dit.test=ProbeStreamingIntegrationTest ...` → exit 0,
  2 tests. Output matches `probe-output.txt` line for line: without an interceptor both uploads arrive
  `te=chunked cl=null size=5000000`; with a no-op `ClientHttpRequestInterceptor` both arrive `te=null cl=5000000`.
  Download: returns at the headers (64 ms), second call refused after `max-wait` (300 ms), 8 bytes read in 1665 ms
  with `timeout=500ms`, stall → `SocketTimeoutException`, call after `close()` OK.
- Planted fault 1 (the note's fault for "a download holds its connection"): `setMaxConnTotal(maxConnections + 1)` in
  `ClientConnectionPools.newPool`. The second call was **still refused** after 300 ms (`setMaxConnPerRoute` stays 1).
  The fault survives the planned test. Code restored, `git status` clean.
- Planted fault 2 (the note's fault for "uploads are never buffered"): the probe's `AppWithInterceptor` is exactly
  that fault. Both uploads turn into `Content-Length: 5000000`, so a test asserting `Transfer-Encoding: chunked`
  and no `Content-Length` fails. **Caught.** This answers the coordinator's question: yes, the tests would fail
  if an upload were buffered.
- Extra probe: a non-streamed `String slow()` on the same 8 × 200 ms body with `timeout=500ms` → read in 1659 ms,
  no timeout. `timeout` is the gap between bytes on **every** call, not only on downloads (finding 1).
- spring-web 7.0.10 jar (`javap`): `InterceptingClientHttpRequest extends AbstractBufferingClientHttpRequest`
  confirmed; `DefaultRestClient` returns the raw body only when the body class equals `InputStream`.
- Probe file deleted afterwards; `git status` clean.

## Track

**Fix is right.** The probe shows every behaviour in ADR 0011 section 7 already works on H1 unchanged. C1 adds tests
and documentation only: no key, default, public API, dependency or main code (ST-AG-15, "a change to tests only",
plus text). The trigger list does not apply: no code that reads untrusted input, masks or logs changes.

## Gap list (ADR 0011 section 7 and A14, the C1 half)

| Requirement | Result | Evidence |
|---|---|---|
| Uploads stream, never buffered | MET (test planned) | probe: `te=chunked cl=null` for `Body` and `InputStreamResource`; fault caught |
| A download holds its connection until closed | MET (test planned) | probe: second call refused within `max-wait` while open; OK after `close()` |
| `timeout` on a download = longest gap between bytes | MET (test planned) | probe: 1.6 s body read with 500 ms timeout; 3 s stall fails |
| Docs show `try-with-resources` | MET in plan | note, "Documents" |
| Streamed upload never retried | out of scope, lane B | ADR 4.3; note says so, agreed |
| Interceptor sees no body for a streamed upload | out of scope, lane A | note says so; Spring buffers in any `ClientHttpRequestInterceptor`, so lane A's `ClientInterceptor` must not be one |
| `total-timeout` stops at the headers for a download | passed to lane B | note, "Lane B" |
| Design note questions 1, 2, 4 (3 too) | MET | each answer is concrete; "no new type" holds, tests only |

## Findings

1. **Should fix, ST-DOC-01 / ST-DOC-08 ("How documentation is checked": every promise checked against the code).**
   The note plans to document "`timeout` as the gap between bytes" for downloads and to touch the "What every client
   gets" row, but that row today reads `docs/modules/http-client.md:111` "the answer to each call must come within
   `timeout` (5s)", and the property table `:133` "The time allowed for the answer to one call". My probe shows a
   non-streamed `String` answer of 8 bytes at 200 ms intervals is read in full (1659 ms) with `timeout=500ms`, so
   that statement is false for every call, not only for downloads: `ClientConnectionPools.java:77`
   `setResponseTimeout` is HttpClient 5's socket read timeout. A download page that says "gap between bytes" next to
   a table that says "whole answer" would contradict itself for one key. **Fix in the note:** the Streaming section and
   the Timeouts row (and the `timeout` row of the property table, plus the `ClientSettings` Javadoc `:17` that says the
   same, ST-DOC-04) state that `timeout` bounds the wait for the first byte and every gap between bytes on any call,
   and that the bound on a whole call is `total-timeout` (planned, lane B). Name the probe or test that proves it.
2. **Minor, ST-TEST-01 (the fault must be one the test catches).** The "Decisions and their tests" table lists
   "the pool's `setMaxConnTotal` raised to 2" as the fault for "a download holds its connection until closed". Planted,
   it survives: `setMaxConnPerRoute` still limits the single route to 1 and the second call is refused as before.
   **Fix in the note:** plant a fault that the test does catch, such as raising both `setMaxConnTotal` and
   `setMaxConnPerRoute` (the pool ignoring `max-connections`), or asserting the hold through the second call's
   `ResourceAccessException` with a fault that removes `setConnectionRequestTimeout`.

## Planted faults

2 planted, 1 caught. Fault 1 (`setMaxConnTotal` only) survived; fault 2 (no-op `ClientHttpRequestInterceptor`,
upload buffered) caught.

## Rules checked on the note

- ST-AG-06: tests and docs only, as asked. ST-AG-14: memory, leaks and speed answered with evidence.
- ST-ENG-16/18, ST-TEST-16: a second stub server, `StreamingDownstream` on the JDK `HttpServer`, next to
  `ScriptedDownstream` (plain sockets, 306 lines, reads no request body). Accepted: reading a 5 MB chunked request
  body by hand in `ScriptedDownstream` would push it toward 400 lines and a second job. Keep it in `testsupport`,
  with Javadoc (ST-ENG-14) and no copied helper from `ScriptedDownstream`.
- ST-TEST-13/15/17: `FilesClient` in `testsupport/clients`, `@Nested` `Downloads`/`Uploads`, 5 MB body as a
  constant built when the test runs.
- ST-TEST-14: the server paces bytes with `Thread.sleep`, which is the stub's behaviour, not the test waiting;
  the test itself must wait with Awaitility or by reading, never `Thread.sleep`.
- ST-DOC-05: the Streaming section needs no `.properties`/`.yml` pair unless it shows a key. ST-DOC-09: page 306
  lines, under 500 after.
- ST-SEC-01 to 06: nothing logged, no secrets, no error response changes.
- ST-AG-10: no file over its limit in the plan.

## Notes (not findings)

- Timing margins: with an 8 × 200 ms body and `timeout=500ms` a 300 ms pause on a loaded CI runner fails the
  "read in full" test. Pick gap and timeout with at least a 2× margin (for example 150 ms gaps, 1 s timeout, total
  1.2 s) to keep the test deterministic. The implementer's choice; not a note defect.
- The note's source line "`DefaultRestClient.isStreamingResult` keeps the response open only for `InputStream` and
  `InputStreamResource`" is partly imprecise: the 7.0.10 bytecode checks `InputStream` only, and the probe shows an
  `InputStreamResource` return type fails. The planned documentation (InputStreamResource not supported as a return
  type) is right.
- A download held open past `connection-lifetime` is fine: ADR 5.3 checks lifetime only when the connection returns.
- `ResponseEntity<InputStream>` streams the same way; `@RequestPart` multipart is rightly out of scope.

## Not verified

The planned tests themselves (none written yet); HTTPS streaming; files above 5 MB; whether lane A's
`ClientInterceptor` design avoids `ClientHttpRequestInterceptor` (lane A's judge).

Not merged; no code changed; the probe file was removed and `git status` is clean.
