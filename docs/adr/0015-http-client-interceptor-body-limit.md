# ADR 0015: A limit on the request body an HTTP client interceptor reads

**Status:** Accepted, 2026-11-10, by a three-model panel (Sonnet 5.5, Opus 5.5, Fable 5.2) under the owner's
autonomy terms for that night, unanimous after four rounds, with the message wording changed by the design-note
review (PR #274, issue #264). The owner may change the default and its reach in both modes. It adds to
[ADR 0011](0011-http-client-starters.md) and replaces none of its decisions; ADR 0010 is not edited.

[← Documentation home](../README.md)

## Context

- `OutgoingRequest.body()` lets an interceptor read a body that is not streamed, such as JSON, to sign it. It copied
  the whole body into memory with no limit (Codex review of PR #274), which breaks ST-ENG-52: every in-memory buffer
  has a limit.
- A tunable value is a property (ST-ENG-33), and the defaults are safe for production (ST-ENG-25).
- Spring Boot has no key for this: it has no interceptor body copy.

## Decision

1. `body()` reads at most `interceptor-body-limit` of a body. The key is under `stratum.http-client.defaults` and
   `stratum.http-client.clients.<name>`; the client's value wins. It is a `DataSize`, set in `.properties`, YAML or
   Java, with IDE metadata. The default is 1 MiB (`HttpClientConstants.DEFAULT_INTERCEPTOR_BODY_LIMIT`).
2. A value from 1 byte to 1 GiB is accepted. `0` and negative values stop startup with `SX-CORE-1003`, through
   `ClientSettings.isInterceptorBodyLimitInRange()` behind `@Valid`; the message names the key, never the value.
3. The limit applies in both modes, `stratum.backward-compatible=true` included. ST-ENG-52's exception points to
   ST-ENG-25, which limits it to the web module's incoming limits; this buffer is internal, and internal behaviour
   always follows Stratum's defaults (ST-ENG-25, ADR 0010 decision 2).
4. The limit is enforced while the body is written: a counting stream refuses the write that would pass it, so no more
   than the limit is accepted. The test of a 64 MiB body checks that the bytes accepted are at most the limit, not
   the allocation, since the copy may grow to about twice the limit before it is taken out.
5. Above the limit, the call fails before anything is sent with a new code, `SX-HTTPC-0005`, status 500, a fault of
   the service itself. It is not retried, it is not remapped to `SX-HTTPC-0001` or `0002`, and the circuit breaker
   ignores it (`ignoreException`), so it is neither a success nor a failure.
6. **Message wording, changed from the panel's.** The panel asked that the message name the client, the limit and the
   key. The caller's error body carries the exception's `detail`, and codes `0001` to `0004` keep it empty, so that a
   caller learns nothing about the service's wiring (ST-SEC-05). So the code's text names the key, `detail` stays
   empty, and the client and the limit, in bytes as `DataSize` prints them, go in the logged cause.

## Rejected

- A fixed limit with no key: against ST-ENG-33.
- No limit: against ST-ENG-52, ST-ENG-33 and ST-SEC-12.
- An empty body above the limit: an interceptor would sign the wrong body without anyone noticing.

## Consequences

- A team that signs bodies up to 1 MB writes nothing. A webhook signer for 5 MB payloads sets
  `interceptor-body-limit: 8MB` on that client.
- A call whose interceptor reads its body holds up to about three times the limit while it runs; concurrent calls add
  up. The headers page says so.
- A streamed body, and a call whose interceptors never read the body, are not affected at any size.
