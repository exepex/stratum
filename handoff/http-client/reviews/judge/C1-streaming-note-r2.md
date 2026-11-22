# Judge verdict: C1 streaming design note (lane C, HTTP client starter), round 2

**Item:** design note `design/http-client-libraries/c1-streaming/c1-design-note.md` (round 2), base `main` at
`aabdae8`. Track: Fix. Review step: design note. Judge model: Claude Opus (round 1 was Fable).

**Verdict: FAIL**, on one should-fix and one minor finding, both fixed by editing the note. Round 1's F1 and F2 are
properly fixed.

I read `AGENTS.md`, `docs/agents/judge.md`, the round-1 verdict, ADR 0011 sections 4 and 7, lane B's
`b1-resilience-design-note.md`, and the H1 code the note cites.

## What I ran

- JDK 25, `./mvnw -pl starters/stratum-starter-http-client -am install -DskipTests` → exit 0.
- The note's probe, plus one method `Resource slowResource()` on `/slow`, as an IT in the starter → exit 0. Output
  as round 1 (stream returns at 83 ms, second call refused after `max-wait` 300 ms, 8 bytes read in 1684 ms with
  `timeout: 500ms`, stall → `SocketTimeoutException`, uploads `te=chunked cl=null`), and the new line:
  `Resource returned after ms 1609 class=ByteArrayResource`. A `Resource` return reads the whole body into memory.
- Planted F2's new fault: `setMaxConnTotal` and `setMaxConnPerRoute` both `maxConnections + 1`
  (`ClientConnectionPools.java:106-107`). The second call while the stream is open now **SUCCEEDED**, so the planned
  "second call fails within `max-wait`" assertion fails. **Caught.**
- spring-web 7.0.10 sources: `ResourceHttpMessageConverter.readInternal:96` copies the body to a byte array whenever
  the target class is assignable from `ByteArrayResource` (so `Resource`); `DefaultRestClient.isStreamingResult:762`
  checks `InputStream` **and** `InputStreamResource` (the note's source line is right; round 1's note on it was not).
- Code restored, probe deleted, `git status` clean.

## Gap list

| Requirement | Result | Evidence |
|---|---|---|
| R1 F1: `timeout` = wait for first byte and each gap, every call | MET | note "What `timeout` means"; places :111, :133, `ClientSettings.java:17`, Streaming section; test for `String` too |
| R1 F1: `total-timeout` named as whole-call bound | MET | same section; matches B1 "so `total-timeout` bounds the whole call" |
| R1 F2: planted pool fault caught | MET | my plant above: second call succeeds, test would fail |
| Uploads stream, never buffered | MET | probe; fault caught in round 1 |
| Download holds connection until closed; close returns it | MET | probe |
| Docs say which download types stream | PARTLY | finding 1 |
| Every place stating the old `timeout` meaning fixed | PARTLY | finding 2 |
| Track Fix with a Javadoc change in main code | MET | text only, no behaviour, key or default change |

## Findings

1. **Should fix, ST-DOC-01 / ST-AG-14 (memory).** The Streaming section plans "the two return types" and
   "`InputStreamResource` not supported", but says nothing of `Resource`, the type Spring's own examples use for a
   file download. Probe: `Resource slowResource()` returned only after the whole body (1609 ms) as a
   `ByteArrayResource` (source `ResourceHttpMessageConverter:96`). A team that writes `Resource` (or `byte[]`) for a
   large export holds the whole file in heap per call, unaware. **Fix:** the Streaming section states that only
   `InputStream` and `ResponseEntity<InputStream>` stream, and that `Resource` and `byte[]` read the whole answer
   into memory. Docs only; no new test needed.
2. **Minor, ST-DOC-04.** The note's list of places misses `HttpClientConstants.java:50`:
   `/** Stratum's default time allowed for the answer to one call. */` on `DEFAULT_TIMEOUT`, the same wrong
   sentence. **Fix:** add it to the list (my `grep -rn "answer to"` over `libs starters docs` found exactly these
   three code/doc places: that line, `ClientSettings.java:17` and `http-client.md:133`, plus `:111`'s wording).

## Planted faults

1 planted (F2's both-limits fault), 1 caught.

## Notes (not findings)

- Cross-lane, for the coordinator and lane B's judge: `b1-resilience-design-note.md` lists "`InputStream`,
  `Resource` or `ResponseEntity` of one" as a streamed download exempt from `total-timeout`. A `Resource` return is
  read in full inside the attempt (probe above), so it is not streamed; B1 should name `InputStream` and
  `ResponseEntity<InputStream>` only.
- B1 enforces `total-timeout` by capping each attempt's per-read timeout at the time left. A downstream that
  trickles bytes with gaps shorter than that can still outlast the deadline. Theoretical for a normal service;
  C1's wording "the whole call is bounded by `total-timeout`" is right only if lane B makes it so. Lane B's call.
- The plant for "`timeout` applied as a deadline on the whole call" is not a concrete code change on H1 (HC5 has
  no whole-response deadline to switch on). The implementer should name the exact change in the Claims, or drop
  the row's fault and rely on the stall test's `setResponseTimeout` removal.
- Round 1's timing-margin note stands: use at least a 2× margin between chunk gap and `timeout`.

## Not verified

The planned tests (none written); `byte[]` return buffering (same converter path, not run); HTTPS streaming.

Not merged; no code changed; probe removed; `git status` clean.
