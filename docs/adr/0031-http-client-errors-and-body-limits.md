# ADR 0031: HTTP client errors, body limits and streaming

**Status:** Accepted, 2026-11-22, by the owner. Its decisions were taken from 2026-11-09 to 2026-11-20; each
gives its date.

One of four records on the HTTP client starters. The keys and their defaults and ranges are in
[ADR 0029](0029-http-client-starters-and-settings.md); this record owns what a failed call throws and answers, the
body limits and streaming.

[← Documentation home](../README.md) · How to use it: [HTTP client: errors](../modules/http-client-errors.md) ·
[What your caller learns](../guides/what-your-caller-learns.md) · [Streaming](../modules/http-client-streaming.md)

## Context

- **A failed call must say whether it may pass,** such as a `503`, or will not, such as a `404`, so that a job or a
  listener can decide. Each caller in a chain that sees `503` sends the request again, so a chain of retries
  multiplies the load.
- **A team often has its own code for one status of another service,** such as `409` for a conflict, and wants it in
  its error answers without writing Java.
- **Every in-memory buffer has a limit (ST-ENG-52).** An interceptor that reads a request body to sign it, and a
  client that reads a successful answer, each copied the whole body with no limit. A tunable value is a property
  (ST-ENG-33), and Spring Boot has no key for either.
- **A runtime code that ended a `CommandLineRunner` or `ApplicationRunner`** was shown as "APPLICATION FAILED TO
  START", with no stack trace and no `error.code`.

## Decision

1. **The codes of a call** (owner, 2026-11-09 to 2026-11-19). Each is a `500` unless the table says otherwise, and
   its status is a default in `stratum.web.error.statuses`, below the service's own values, so the service's value
   wins ([ADR 0026](0026-error-format-and-backward-compatible-switch.md), decision 8). Each code has its row, with
   its fix, on the [error codes](../guides/error-codes.md) page.

   | Code | When | Default status |
   |---|---|---|
   | `SX-HTTPC-0001` | No answer in time | `504` |
   | `SX-HTTPC-0002` | Unreachable: a connect failure, a full pool, or a service token that cannot be fetched | `503` |
   | `SX-HTTPC-0003` | Not sent: the breaker is open | `503` |
   | `SX-HTTPC-0004` | Not sent: token relay with no verified caller, a setup mistake of the service | `500` |
   | `SX-HTTPC-0005` | Not sent: an interceptor read a body over `interceptor-body-limit` (2026-11-10) | `500` |
   | `SX-HTTPC-0006` | A successful answer over `response-body-limit` (2026-11-10) | `500` |
   | `SX-HTTPC-0007` | A final error answer, such as `400`, `404` or `500` (2026-11-19) | `500` |
   | `SX-HTTPC-0008` | An answer whose body cannot be read (2026-11-19) | `500` |
   | `SX-HTTPC-0009` | `429`, `502`, `503` or `504` after the retries (2026-11-19) | `500` |

2. **What an error answer throws** (owner, 2026-11-09 and 2026-11-19).
   1. A call of a client's interface that gets an error answer, after its retries, throws a `DownstreamException`,
      which carries the status, client, endpoint, code, trace ID and at most 64 KB of the error body. Its code is
      `SX-HTTPC-0009` or `SX-HTTPC-0007`, as the table says.
   2. An answer whose body cannot be read, such as JSON that does not match, throws `UnreadableAnswerException` with
      `SX-HTTPC-0008`. It is a Spring `RestClientException`, so a `catch` of that keeps working. The other service
      may have done the work, so it is never sent again and never counts for the circuit breaker.
   3. Uncaught, each answers its status with Stratum's error body, in both modes: both exceptions implement
      `FailureWithErrorCode` (ADR 0026, decision 7). A status such as `503` is not the default for an error answer,
      because it would make each caller in a chain send the request again.
   4. With token relay, a `401` is thrown as a `DownstreamException` with status `401`
      ([ADR 0030](0030-http-client-auth-resilience-and-connections.md), decision 1.3).
   5. The other service's body is never passed on to our caller.
3. **The service's own code for an error status** (owner, 2026-11-20).
   1. A status listed in `errors`, under `defaults`, a client or an operation, still throws a `DownstreamException`,
      with its status, headers and body; only `getErrorCode()` returns the service's code. An operation's code wins
      over its client's, and a client's over `defaults`'. Every other status keeps `SX-HTTPC-0007` or
      `SX-HTTPC-0009`.
   2. The caller gets that code, with the status `stratum.web.error.statuses` gives it, or `500`, and the fixed text
      of `SX-HTTPC-0007`, never the code's own description or the other service's body.
   3. Retries and the circuit breaker see the other service's own answer, not the mapped one. A customizer's
      `mapError` runs after the code is set, and may still throw the service's own exception.
   4. It applies to the calls of a client's interfaces. A generated interface has no `@StratumOperation`, so it takes
      the client's and the defaults' codes.
4. **What a client's beans throw** (owner, 2026-11-10). A failed call through a client's `RestClient` or
   `RestTemplate` bean throws Spring's own exceptions, as a client the team built would, because a generated
   client's own error handling expects them. Three Stratum codes remain, as for an interface: `SX-HTTPC-0003` for a
   call an open breaker refused, and, from the credential that runs on each attempt, `SX-HTTPC-0002` for a service
   token that cannot be fetched and `SX-HTTPC-0004` for a token-relay call with no verified caller. An answer over
   `response-body-limit` fails with Spring's `RestClientException`, whose cause's message starts with
   `SX-HTTPC-0006`. `DownstreamException`, `UnreadableAnswerException`, `errors` and `mapError` are for interfaces.
5. **The limit on the request body an interceptor reads** (2026-11-10, under the owner's autonomy terms; issue
   #264).
   1. `OutgoingRequest.body()` reads at most `interceptor-body-limit` of a body. The limit applies in both modes: the
      buffer is internal, and internal behaviour always follows Stratum's defaults (ADR 0026, decision 2).
   2. It is enforced while the body is written: a counting stream refuses the write that would pass it, so no more
      than the limit is accepted.
   3. Above the limit, the call fails before anything is sent with `SX-HTTPC-0005`. It is not retried, not remapped
      to `SX-HTTPC-0001` or `SX-HTTPC-0002`, and the circuit breaker ignores it, so it is neither a success nor a
      failure.
   4. The code's text names the key; `detail` stays empty, so a caller learns nothing about the service's wiring
      (ST-SEC-05); the client and the limit, in bytes, go in the logged cause.
6. **The limit on a successful answer's body** (owner, 2026-11-10, the design note of #236).
   1. A client reads at most `response-body-limit` of a successful answer's body, status below 400, counted after
      gzip is undone, in both modes. It applies to a client's interfaces, its beans and its mocks.
   2. A read that passes the limit fails with `SX-HTTPC-0006`; an answer whose `Content-Length` is over it fails on
      the first read, before a byte is passed on. The connection is dropped, not reused. The call is not retried, and
      the circuit breaker counts it as a success: the other service answered.
   3. A body the call does not read, or reads only in part, is drained when the answer closes through the same
      count, and dropped with its connection once it passes the limit, without an error.
   4. A method returning `InputStream` or `ResponseEntity<InputStream>` is not limited: it streams. A `Resource` is
      read whole, so it is limited. A token fetch is always limited, even during a streamed download.
   5. The caller's error body names nothing internal: `detail` stays empty, and the logged cause names the client and
      the limit in bytes, never a byte of the body.
7. **Streaming** (owner, 2026-11-09). All uploads and downloads can stream. A download holds its connection until it
   is closed. On a download, `read-timeout` is the longest gap between bytes. An interceptor sees no body for a
   streamed upload.
8. **The startup failure report shows only startup codes** (owner, 2026-11-19, #403). Stratum's startup failure
   analyzer reports only a code that has a fix, which is a code that stops startup. A code that ends a
   `CommandLineRunner` or an `ApplicationRunner`, such as a failed call's, is left to Spring Boot, which logs
   "Application run failed" with the stack trace, and the logging module adds `error.code`.

## Rejected

- No body limit, or a fixed one with no key: against ST-ENG-52, ST-ENG-33 and ST-SEC-12.
- An empty body above the interceptor limit: an interceptor would sign the wrong body without anyone noticing.
- A response limit per endpoint: planned later, in a follow-up issue, if a service needs it.
- A Spring interceptor in place of Apache's handler for the response limit: it would count the bytes before gzip is
  undone.
- Wrapping a mapped answer in a `StratumException`: a `catch` of `DownstreamException` would stop working.

## Consequences

- A `catch` of `DownstreamException` keeps working for every error answer, a mapped one included, and a job or a
  listener reads the code from `getErrorCode()`.
- A team that signs bodies up to 1 MB writes nothing; a webhook signer for 5 MB payloads raises
  `interceptor-body-limit` on that client. A call whose interceptor reads its body holds up to about three times the
  limit while it runs; concurrent calls add up.
- A service whose answers stay under 32 MB writes nothing; one that reads larger answers raises the limit for that
  client, or returns an `InputStream`. The worst case of memory per client is about `max-connections` times the
  limit.
- A streamed body, and a call whose interceptors never read the body, are not affected at any size.
- A failed call in a runner keeps its stack trace and `error.code` in the log.
