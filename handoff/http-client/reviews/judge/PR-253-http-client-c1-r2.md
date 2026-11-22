# Judge verdict: PR #253, C1 streaming (lane C), branch `claude/project-thread-79g1e3`, head `da48d40`

Track: Fix. Review step: code, round 2 (round 1 was Opus at `8593836`). Judge model: Claude Fable. I read
`AGENTS.md` and all six topic files, the round-1 verdict, the design note (round 3) and both note verdicts.

**PASS.** The one round-1 finding is fixed, and I found no new finding of any severity.

## Gate
Open, ready for review, not a draft. Base `main` at `4a3d942` is the merge base (rebased after #252). At `da48d40` all
seven check runs are green (Verify and SBOM, CodeQL, SonarCloud 0 new issues, wiki check). Both Codex threads
(`@Builder` on `Upload`, IPv6 URL) are fixed in `8593836`, answered and resolved; no open thread. Claims complete,
design note present. `8593836..da48d40` changes only `docs/modules/http-client.md` (+4 −2).

## Gap list
| Requirement | Result | Evidence |
|---|---|---|
| Round-1 F1: docs say closing a download early reads the rest first | MET | `http-client.md:275-278`; my probe below confirms every word |
| `InputStream` download holds its connection until closed, then gives it back | MET | `holdsConnectionUntilClosed`; plant A caught |
| `ResponseEntity<InputStream>` streams the same way | MET | `entityHoldsConnectionUntilClosed`; plant A caught |
| `timeout` is the gap between bytes on every call | MET | `timeoutIsTheGapBetweenBytes`, `textAnswerTimeoutIsTheGapBetweenBytes`; page :111, :133; `ClientSettings.java:18-20`; `HttpClientConstants.java:50-52` |
| `total-timeout` named as the bound on the whole call | MET | `http-client.md:111` "a limit on the whole call comes with the planned `total-timeout`" |
| A stall longer than `timeout` fails the read | MET | `stallFailsTheRead` |
| Uploads stream chunked without `Content-Length`, also via `restClient(name)` | MET | `Uploads` (3 tests); plant B caught all three |
| Only `InputStream` and `ResponseEntity<InputStream>` stream; `byte[]`, `Resource` read whole | MET | `http-client.md:250-253` |
| Streaming removed from Planned | MET | `http-client.md:383` |
| Stale "answer to one/each call" wording gone | MET | `grep -rn "answer to" libs starters docs` → 0 hits outside `docs/adr/` (ADR 0011 unedited, as round 1 accepted) |
| Line counts | MET | `http-client.md` 396/500; largest Java `StreamingDownstream` 192, `StreamingIntegrationTest` 191; all 9 files match the Claims |

## Findings
None.

## What I ran
- JDK 25, Maven 3.10.0 (wrapper zip, sha256 checked):
  `mvn -pl libs/stratum-http-client,starters/stratum-starter-http-client -am verify` at `da48d40` → exit 0
  (Spotless, Checkstyle, PMD, SpotBugs, JaCoCo; 42 ITs in the starter, 8 of them in `StreamingIntegrationTest`,
  0 failures).
- Probe of the new sentence (`FilesClient` against `StreamingDownstream`, `timeout: 500ms`, `max-connections: 1`):
  `download()`, read 1 byte, `close()` → 1394 ms (the 7 remaining bytes at 200 ms); `downloadStalled()`, read 1
  byte, `close()` → `SocketTimeoutException` after 501 ms; the next `download()` on the same single-connection pool
  then succeeded (headers after 204 ms, full body). So closing early drains, a stall ends the close with
  `SocketTimeoutException`, and the connection is given back either way. Probe deleted; `git status` clean.

## Planted faults
2 planted (main code only, `StreamingIntegrationTest` run alone, code restored, `git status` clean), 2 caught:
- A `setMaxConnTotal` and `setMaxConnPerRoute` both `maxConnections + 1` (`ClientConnectionPools.java:106-107`) →
  `holdsConnectionUntilClosed` and `entityHoldsConnectionUntilClosed` fail.
- B a no-op `requestInterceptor` on the builder in `StratumClientBuilders.configure` → `writtenBodyIsStreamed`,
  `resourceIsStreamed` and `restClientOfClientStreams` fail.
Round 1's five plants (including `setResponseTimeout` removed) stand; the code they covered is unchanged since.

## Rules checked
| Rule | Result |
|---|---|
| ST-ENG-42/43 | `@Builder` on the 3-field `Upload` record, built with it in `takeUpload` and `assertStreamed` |
| ST-ENG-46 | lambdas single expressions; the upload writer is the named `writeUpload` |
| ST-ENG-47 | only the `start` factory is static in `StreamingDownstream` |
| ST-ENG-14/15 | Javadoc on every new type, method and constant; brief |
| ST-ENG-41 | `@NullMarked` `package-info` for `files` |
| ST-ENG-56/57 | no new main type; main change is Javadoc only |
| ST-TEST-14 | no `Thread.sleep`; the server paces on a latch that `close()` opens |
| ST-TEST-15/17 | `@Nested` `Downloads` and `Uploads` |
| ST-TEST-16 / ST-ENG-18 | reuses `CallChecks.assertFailsFast`, `RestClientTestData.FAILS_WITHIN`, `ScriptedDownstream.url()`'s URI pattern |
| ST-DOC-05 | no new key, so no properties/YAML pair needed |
| ST-SEC-01 to 06 | nothing logged, no secrets, no untrusted input |
| ST-AG-10 | all files under limit |
Track Fix stands: no key, default, public API, dependency or behaviour change.

## Notes (not findings)
- Lane B: the page's row :111 says `total-timeout` bounds the whole call; for a streamed download B1 covers the call
  only until the headers. When B1 lands, its page text should say so in the Streaming section. Not C1's.
- `stallFailsTheRead` closes the stream after the read timed out; HC5 had already aborted the connection, so that
  close is quick and the test stays under `FAILS_WITHIN` (1.5 s). Observed, not a risk.

Not merged; nothing changed; probe removed; `git status` clean.
