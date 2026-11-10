# ADR 0017: A limit on the body of a successful answer an HTTP client reads

**Status:** Accepted, 2026-11-10, by the owner's nine decisions on the design note of #236, whose second round the
judge passed. It adds to [ADR 0011](0011-http-client-starters.md) and replaces none of its decisions. It is the
answer-side twin of [ADR 0015](0015-http-client-interceptor-body-limit.md).

[← Documentation home](../README.md)

## Context

- A Stratum client read an error body up to 64 KB, but a successful answer whole, with no limit. A broken or hostile
  service could fill the caller's memory, and a body the call does not read, such as for a method returning `void`,
  was drained with no limit when the answer closed. This breaks ST-ENG-52: bound the whole response.
- A tunable value is a property (ST-ENG-33), and the defaults are safe for production (ST-ENG-25).
- Spring Boot has no key for this on `RestClient`.

## Decision

1. A client reads at most `response-body-limit` of a successful answer's body, status below 400, counted after gzip is
   undone. The key is under `stratum.http-client.defaults` and `stratum.http-client.clients.<name>`; the client's
   value wins. It is a `DataSize`, set in `.properties`, YAML or Java. The default is 32 MB.
2. `-1` turns the limit off. Any other value must be from 1 byte to 1 GiB; one that is not stops startup with
   `SX-CORE-1003`, naming the key, never the value.
3. A read that passes the limit fails with `SX-HTTPC-0006`, status 500; an answer whose `Content-Length` is over it
   fails on the first read, before a byte is passed on. The connection is dropped, not reused. The call is not
   retried, and the circuit breaker counts it as a success: the other service answered.
4. A body the call does not read, or reads only in part, is drained when the answer closes through the same count, and
   dropped with its connection once it passes the limit, without an error.
5. A method returning `InputStream` or `ResponseEntity<InputStream>` is not limited: it streams. A `Resource` is read
   whole, so it is limited. A token fetch is always limited, even during a streamed download.
6. The limit applies in both modes, `stratum.backward-compatible=true` included, as ADR 0015 decision 3 explains for
   the interceptor body limit. It applies to a client's interfaces, its `RestClient` and `RestTemplate` beans and its
   mocks.
7. The caller's error body names nothing internal: `detail` stays empty, and the logged cause names the client and the
   limit in bytes, never a byte of the body.

## Rejected

- No limit: against ST-ENG-52 and ST-SEC-12.
- A limit per endpoint: planned later, in a follow-up issue, if a service needs it.
- A Spring interceptor in place of Apache's handler: it would count the bytes before gzip is undone.

## Consequences

- A service whose answers stay under 32 MB writes nothing. One that reads larger answers raises the limit for that
  client, or returns an `InputStream`.
- The worst case of memory per client is about `max-connections` times the limit, against no bound before.
