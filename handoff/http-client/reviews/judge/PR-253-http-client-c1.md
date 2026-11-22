# Judge verdict: PR #253, C1 streaming (lane C), branch `claude/project-thread-79g1e3`, head `8593836`

Track: Fix. Review step: code, round 1. Judge model: Claude Opus. I read `AGENTS.md` and all six topic files.

**FAIL** on one should-fix finding (a missing sentence in the docs). Everything else holds.

## Gate
PR ready for review; CI green at `8593836` (Verify and SBOM, CodeQL, SonarCloud: 0 new issues); both Codex threads
(`@Builder` on `Upload`, IPv6 URL) fixed in `8593836` and resolved. Claims and design note present.

## Gap list
| Requirement | Result | Evidence |
|---|---|---|
| `InputStream` download holds its connection until closed, then gives it back | MET | `holdsConnectionUntilClosed`; fault F1 caught |
| `ResponseEntity<InputStream>` streams the same way | MET | `entityHoldsConnectionUntilClosed`; F1 caught |
| R1: `timeout` is the gap between bytes on every call | MET | `timeoutIsTheGapBetweenBytes` (download) and `textAnswerTimeoutIsTheGapBetweenBytes` (`String`); page :111, :133; `ClientSettings` Javadoc |
| R1: `total-timeout` named as the bound on the whole call | MET | `http-client.md:111` "a limit on the whole call comes with the planned `total-timeout`" |
| A stall longer than `timeout` fails the read | MET | `stallFailsTheRead`; F2 caught |
| Uploads stream (`Body`, `InputStreamResource`), chunked, no `Content-Length` | MET | `Uploads`; F3, F4, F5 caught |
| `restClient(name)` uploads stream too | MET | `restClientOfClientStreams`; F4 (interceptor only on that path) caught |
| R1: planted pool fault raises both limits and is caught | MET | F1 below, both `+ 1` |
| R2 F1: only `InputStream` and `ResponseEntity<InputStream>` stream; `byte[]` and `Resource` read in full | MET | `http-client.md` Streaming, first paragraph |
| R2 F2: `HttpClientConstants` `DEFAULT_TIMEOUT` Javadoc fixed | MET | `HttpClientConstants.java:50-52`; `grep "answer to"` over libs, starters, docs: no stale hit outside ADRs |
| Docs say what closing a download does | PARTLY | finding 1 |
| Streaming removed from Planned | MET | `http-client.md:381` |
| Line counts | MET | largest: `http-client.md` 394 (limit 500), `StreamingIntegrationTest` 191, `StreamingDownstream` 192 |

## Findings
1. **Should fix, ST-DOC-01 (docs true to the code), ST-AG-14 (speed).** The Streaming section says "Close every
   download ... An open download holds one of the client's connections until it is closed", but not that closing a
   download before its end first reads the rest of it. Spring 7.0.10 `HttpComponentsClientHttpResponse.close()` calls
   `EntityUtils.consume(entity)` before `response.close()` (javap). Probe on this head (`FilesClient.download()`,
   read 1 byte, `close()`): close took 1394 ms, the 7 remaining bytes at 200 ms each; on `/stall` close waited the
   500 ms `timeout`, then threw `SocketTimeoutException`. A team that stops a 2 GB export after a disk-full error
   keeps its thread and connection until the whole file has arrived. **Fix:** one sentence in the Streaming section,
   such as "Closing a download before its end reads the rest of it first, so that the connection can be reused; a
   pause in it longer than `timeout` ends the close with `SocketTimeoutException`." No test needed.

## Planted faults
5 planted, 5 caught (main code only, `StreamingIntegrationTest` run alone, code restored, `git status` clean):
- F1 `setMaxConnTotal` and `setMaxConnPerRoute` both `maxConnections + 1` → both hold tests fail.
- F2 `setResponseTimeout(Timeout.DISABLED)` → `stallFailsTheRead` fails.
- F3 `BufferingClientHttpRequestFactory` around the pool's factory in `StratumClientBuilders.configure` (buffers
  requests and answers) → both hold tests and all three upload tests fail.
- F4 no-op interceptor only in `DefaultStratumHttpClients.build` → `restClientOfClientStreams` fails.
- F5 no-op interceptor in `StratumClientBuilders.configure` → all three upload tests fail.

Build: `./mvnw -pl libs/stratum-http-client,starters/stratum-starter-http-client verify` at `8593836`, JDK 25 → exit 0
(Spotless, Checkstyle, PMD, SpotBugs, JaCoCo, 42 ITs).

## Rules checked
ST-ENG-42/43 (`@Builder` on the 3-field `Upload`, built with it), ST-ENG-46 (named `writeUpload`), ST-ENG-47 (only
the `start` factory is static), ST-ENG-14/15 (Javadoc on every new type and method), ST-ENG-41 (`@NullMarked`
`package-info` for `files`), ST-ENG-56 (no new main type), ST-TEST-14 (no `Thread.sleep`; the server paces on a
latch), ST-TEST-15/17 (`@Nested` `Downloads`, `Uploads`), ST-TEST-16/ST-ENG-18 (reuses `CallChecks.assertFailsFast`,
`FAILS_WITHIN`), ST-DOC-05 (no new key), ST-SEC-01 to 06 (nothing logged, no secrets). Track Fix stands: Javadoc and
docs text only in main code.

## Notes
- Timing margin: 200 ms gaps against a 500 ms `timeout` (2.5x), as round 1 asked.
- `FileSystemResource` uploads stream with a `Content-Length` (known size), not chunked; the page does not claim
  otherwise. Not run.
