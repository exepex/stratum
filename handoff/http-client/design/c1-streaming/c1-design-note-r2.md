# Design note: C1, streaming uploads and downloads on the blocking HTTP client starter, round 2

**Item:** "streaming" (ADR 0011 section 7, Follow-up items; gap review A14). Lane C of the HTTP client starter,
started on the owner's message of 2026-11-09 13:26Z ("parallel lanes ... work autonomously as per the previous
agreements to finish HTTP client starter"). Base: `main` at aabdae8 (H1, PR #242, merged).

**Track proposed: Fix.** The probe below shows H1 already streams in both directions, so C1 adds tests that pin
ADR 0011 section 7 and the documentation of it, and corrects the `timeout` Javadoc (F1). No key, default, public API or dependency changes. If the
coordinator's vote says Feature, question 3 is answered too.

**Probe (2026-11-09, Boot 4.1.2, spring-web 7.0.10, HC5 on H1's pool; test and output in this folder):**

| Case | Result |
|---|---|
| `InputStream download()` | returns at the headers; the body arrives as it is read |
| a second call while that stream is open, `max-connections: 1`, `max-wait: 300ms` | refused after 300ms: the stream holds its connection |
| a call after the stream is closed | works: closing gives the connection back |
| 8 bytes 200ms apart, `timeout: 500ms` (1.6s in total) | read in full: `timeout` is the gap between bytes |
| a 3s stall in the body, `timeout: 500ms` | `SocketTimeoutException: Read timed out` |
| `ResponseEntity<InputStream>` | streams the same way |
| `ResponseEntity<InputStreamResource>` | fails: `Unsupported resource class: InputStreamResource` |
| upload, `@RequestBody StreamingHttpOutputMessage.Body` or `InputStreamResource`, 5 MB | sent `Transfer-Encoding: chunked`, no `Content-Length`: not buffered |
| the same upload with any `ClientHttpRequestInterceptor` on the builder | sent with `Content-Length: 5000000`: Spring buffered the whole body in memory |

The last row is why lane B moved retry and the breaker into an `HttpExchangeAdapterDecorator` (coordinator,
13:33Z). Source: spring-web 7.0.10 `InterceptingClientHttpRequest extends AbstractBufferingClientHttpRequest`, and
`DefaultRestClient.isStreamingResult` keeps the response open only for `InputStream` and `InputStreamResource`.

## 1. Who uses it, and does anything change for them?
Teams that move files or large payloads (reports, exports, documents) through a `@StratumClient` interface, new,
existing or hybrid, on the bundle or the starter alone. Nothing changes: the tests prove today's behaviour. They write
`InputStream` or `ResponseEntity<InputStream>` as a return type, or `StreamingHttpOutputMessage.Body`/`Resource` as
`@RequestBody`, and close a download with `try-with-resources`. No new type, so nothing new for ST-ENG-56.

## 2. How is it built, and why this way?
Spring's HTTP interface and `RestClient` already stream, and H1's HC5 pool already sends chunked and bounds reads per
socket read; reused unchanged. The alternative, a Stratum streaming type or `stratum.http-client` key, was dropped:
Spring's types suffice and ADR 0011 lists no key for it. New test code only: `StreamingDownstream` (JDK `HttpServer`)
in the starter's `testsupport`, beside `ScriptedDownstream`, which reads no request body and is at 306 lines.

## 3. Is it safe and fast?
Memory: the upload test fails when the body is buffered (`Content-Length` present), which guards every later lane.
Leaks: an unclosed download holds a pool connection until closed; `max-wait` then refuses calls, so the leak is
bounded and visible; docs show `try-with-resources`. No parsing, regex or masking; no thread hop (caller's thread).

## 4. Which rules and owner decisions does it follow?
ADR 0011 section 7 and A14 (agreed 00:28Z); ST-ENG-25, ST-ENG-51 (a streamed upload is never retried, built and
tested by lane B's decorator), ST-TEST-01 (faults below), ST-DOC rules for `docs/modules/http-client.md`.
Spring facts: probe above and the two spring-web 7.0.10 sources named. Docs: the module page and its Planned list.

## Details

### Decisions and their tests

New `it/StreamingIntegrationTest`, against `StreamingDownstream`, with a `FilesClient` interface in
`testsupport/clients`. `@Nested` groups `Downloads` and `Uploads`.

| Decision (ADR 0011 section 7, A14) | Test | Planted fault it catches |
|---|---|---|
| A download holds its connection until closed | with `max-connections: 1`, a second call fails within `max-wait` while the stream is open, and succeeds after `close()` | the pool ignores `max-connections` (`setMaxConnTotal` and `setMaxConnPerRoute` both raised to 2) |
| Closing gives the connection back | the call after `close()` succeeds on the same single connection | (same test, second half) |
| `timeout` on a download is the gap between bytes | a body sent in slow chunks, longer in total than `timeout`, is read in full | `timeout` applied as a deadline on the whole call instead of per read |
| A stall longer than `timeout` fails the read | `SocketTimeoutException` within `timeout` plus margin | `setResponseTimeout` removed |
| `ResponseEntity<InputStream>` streams too | same hold-and-release check | - |
| Uploads stream, never buffered | `Body` and `InputStreamResource` arrive with `Transfer-Encoding: chunked` and no `Content-Length` | a no-op `ClientHttpRequestInterceptor` added in `StratumClientGroups` |

Retry of a streamed upload and an interceptor's empty `body()` are not in C1: there is no retry (lane B) and no
`ClientInterceptor` (lane A) yet. Each lane tests its own half of A14 against `StreamingDownstream`.

### Lane B: `total-timeout` and downloads
A download returns at the headers. Lane B's deadline covers the call until then, not the reading of the stream after
it; otherwise an 8s `total-timeout` would cut every long download. Passed to lane B; their test, not C1's.

### What `timeout` means (round 1, F1)
`timeout` is Apache HC5's socket read timeout (`ClientConnectionPools.java:77`, `setResponseTimeout`) on every call,
not only downloads: it bounds the wait for the first byte and each gap between bytes, never the whole answer. The
Fable judge's probe read an 8-byte `String` answer sent 200ms apart in 1659ms with `timeout: 500ms`. H1's page and
Javadoc say "the answer to each call must come within `timeout`", which is wrong. C1 fixes them to: "the longest wait
for the first byte of the answer and between two bytes of it; the whole call is bounded by `total-timeout` (planned)".
Places: `docs/modules/http-client.md:111` (What every client gets) and `:133` (property table), the `ClientSettings`
`timeout` Javadoc (`ClientSettings.java:17`), which is also the IDE description, and the Streaming section. Test:
`StreamingIntegrationTest` "slow chunks longer in total than `timeout` are read in full", for a download and for a
`String` answer. This is a Javadoc fix in main code, so C1 is no longer tests and docs only; still the Fix track
(restores documented behaviour to the truth, no behaviour change).

### Documents
`docs/modules/http-client.md`: a "Streaming" section (the two return types, the two body types, `try-with-resources`,
`timeout` as the gap between bytes, `InputStreamResource` not supported as a return type), the "What every client
gets" table row, and streaming removed from "Planned". The page is 306 lines; under 500 after.

### Not in C1
Multipart uploads (`@RequestPart`): Spring's multipart writer buffers each part's headers only, not verified for large
parts; not asked. The reactive starter's streaming (`Flux<DataBuffer>`) is its own note.

## Not verified
Files beyond 5 MB (the buffering check holds for any size, as `Content-Length` shows it); HTTPS streaming (same
HC5 path, not run). The probe ran on JDK 25.

## Round 1 findings (reviews/judge/C1-streaming-note.md)
- F1: `timeout` described as the gap between bytes on every call, in the page row, the property table, the
  `ClientSettings` Javadoc and the Streaming section; `total-timeout` named as the whole-call bound; test added for a
  `String` answer too.
- F2: the planted fault now raises both pool limits, which the hold test catches.
