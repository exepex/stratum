# ADR 0016: The HTTP client's call log key, its redaction signal and its meters

**Status:** Accepted, 2026-11-10, by the owner, who took its decisions on 2026-11-10 (issue
[#266](https://github.com/exepex/stratum/issues/266)). Adds one key to decision 2.4 of
[ADR 0011](0011-http-client-starters.md), a 20th, and settles the open points of its decision 8. Every other part of
ADR 0011, and all of [ADR 0012](0012-http-client-changes-found-while-building.md),
[ADR 0013](0013-http-client-retry-and-circuit-breaker-keys.md) and
[ADR 0014](0014-http-client-beans-that-retry.md), stands.

[← Documentation home](../README.md)

## Context

- ADR 0011 decision 8.1 gives each failed call one log line with the `uri` template, and the real `url` only while the
  logging starter's redaction runs. It left the signal that tells the client starter so to this item, and forbade the
  client starter to read the logging starter's settings or import its classes (ST-ENG-11).
- ADR 0011 lists 19 keys per client. A team that onboards a partner wants every call in the log for a while, and a
  health pinger whose failures are expected wants none; no key allowed either.
- ADR 0011 decision 8.3 asks for the pool gauges of Micrometer's Apache HttpClient 5 binder, and decision 4.5 for
  refused calls to be counted. Retries per call and the breaker's state had no meter either.

## Decision

1. **A new key, `call-log`**: `errors` (the default), `all` or `none`, under `stratum.http-client.defaults` and each
   client, and in Java as `ClientSettings.callLog(CallLog)`; the client's value wins (owner, 2026-11-10, decision 3).
   - `errors`: one WARN line per call that ends with an error answer (4xx or 5xx, after its retries), a timeout, no
     connection or a failure inside the service. A call the open breaker refused writes no line, and a 404 that a
     method returning `Optional` reads as empty is not a failure (owner, 2026-11-10, decision 2).
   - `all`: also one INFO line per successful call. `none`: no line; the meters still count every call.
2. **The signal is a marker bean.** `stratum-core` holds an empty, internal type, `LogRedaction`; the logging starter
   declares a bean of it in the auto-configuration that exists only while its module is on, which is exactly while
   its redaction runs. The client starter asks Spring for the bean once, at startup. With it, the line writes its
   facts as key-value fields, `url` among them; without it, it writes them in the message, with `uri` cut at its first
   `?` and at 256 characters, and no `url`.
3. **Stratum's own meters, tagged `client`** (owner, 2026-11-10, decision 1), when Micrometer and a `MeterRegistry`
   bean are present: the timer `stratum.http.client.calls` by `outcome`, the counter `stratum.http.client.retries`, the
   gauge `stratum.http.client.circuitbreaker.state` by `state`, and Micrometer's four pool gauges per client pool
   (`_shared` for the shared pool; token pools have none). `resilience4j-micrometer` is not used.
4. **Of #237, only the pool-full counter**, `stratum.http.client.pool.refused`: an attempt that got no connection
   within `max-wait` (owner, 2026-11-10, decision 4). The wait timer stays open in #237.
5. Tags and the line's `uri` hold the template only; the line never holds a header, a body, an exception message or
   the status text.

## Consequences

- A service that adds the starter and sets nothing gets one WARN per failed call and, with Spring Boot's Actuator or
  the observability starter, the meters; it writes no code.
- **What the redaction does not mask in `url`** (owner, 2026-11-10, option A): a value the redactor finds by its shape
  is not found once Spring percent-encodes a character in it. That covers an email (`@` becomes `%40`), a card number
  or IBAN written with spaces (`%20`), and any of a service's `extra-patterns` that needs such a character; they are
  written as sent. Named secret parameters (`api_key=`, `email=`), JWTs, a URL's user and password, and plain emails,
  cards and IBANs are masked. Issue [#273](https://github.com/exepex/stratum/issues/273) teaches the redactor to read
  percent-escapes. The documentation tells teams never to put personal data in a URL.
- A value a team joins into a template's path, `uri("/users/" + email)`, is not caught without the redaction; the
  documentation requires `{}` placeholders.
- Without the logging starter there is no cap on the lines: a storm of 4xx answers, which the breaker does not count,
  writes one line per call. With it, its limit of 5000 WARN lines per logger per second applies to each client's
  logger, `stratum.http-client.<client>`.
- A `RestTemplate` call has no template: its line says `uri=none`.
- **How the decision weighs (ST-AG-14):** per call, one record built after the retries and one enum check at
  `errors`, the meters from a per-client map made once (speed, throughput); at most one line per failed call and none
  per refused call (log volume); per-client maps bounded by the configured clients, nothing kept per call (memory);
  `url` only under redaction, templates only in tags (data leaks, security); one class each for the line, the outcome
  and the meters (maintainability); meters per client and outcome, never per URL (scalability).
