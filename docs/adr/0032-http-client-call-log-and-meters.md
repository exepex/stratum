# ADR 0032: The HTTP client's call log, meters and spans

**Status:** Accepted, 2026-11-22, by the owner. Its decisions were taken on 2026-11-09 and 2026-11-10 (issue
[#266](https://github.com/exepex/stratum/issues/266)); each gives its date.

One of four records on the HTTP client starters. The key `call-log` and its default are in
[ADR 0029](0029-http-client-starters-and-settings.md); this record owns what the line and the meters hold.

[← Documentation home](../README.md) · How to use it:
[HTTP client: call log and meters](../modules/http-client-observability.md)

## Context

- **Incident work needs the real URL of a failed call,** with its path and query values, not only its template. A
  URL can hold personal data, so it may be logged only after the logging starter's redaction (ST-SEC-03).
- **The client starter must learn whether that redaction runs** without reading the logging starter's settings or
  importing its classes (ST-ENG-11).
- **A team that onboards a partner wants every call in the log for a while,** and a health pinger whose failures are
  expected wants none.
- **Retries per call, the breaker's state and refused calls** need a meter, beside the pool gauges of Micrometer's
  Apache HttpClient 5 binder.

## Decision

1. **The call log line** (owner, 2026-11-09 and 2026-11-10).
   1. With `call-log: errors`, the default, a call writes one WARN line when it ends with an error answer (4xx or
      5xx, after its retries), a timeout, no connection or a failure inside the service. A call the open breaker
      refused writes no line, and a 404 that a method returning `Optional` reads as empty is not a failure. With
      `all`, a successful call also writes one INFO line; with `none`, no line, and the meters still count every call.
   2. While the logging starter's redaction runs, the line writes its facts as key-value fields: the real `url`, with
      path and query values kept, and the `uri` template; the redaction runs over it. Without it, because the logging
      starter is absent or switched off, the line writes its facts in the message, with `uri` cut at its first `?`
      and at 256 characters, and no `url`.
   3. The signal is a marker bean. `stratum-core` holds an empty, internal type, `LogRedaction`; the logging starter
      declares a bean of it in the auto-configuration that exists only while its module is on, which is exactly while
      its redaction runs. The client starter asks Spring for the bean once, at startup.
   4. The line never holds a header, a body, an exception message or the status text.
2. **Metric tags and span names use only the URI template** (owner, 2026-11-09). The span's URL attribute is the
   observability starter's: the full URL with user info removed. Its query values are masked like the logs, an
   observability item ([#240](https://github.com/exepex/stratum/issues/240)).
3. **Stratum's own meters, tagged `client`** (owner, 2026-11-10), when Micrometer and a `MeterRegistry` bean are
   present: the timer `stratum.http.client.calls` by `outcome`, the counter `stratum.http.client.retries`, the gauge
   `stratum.http.client.circuitbreaker.state` by `state`, the counter `stratum.http.client.pool.refused` for an
   attempt that got no connection within `max-wait`, and Micrometer's four pool gauges per client pool: max, busy,
   idle and waiting (`_shared` for the shared pool; token pools have none). `resilience4j-micrometer` is not used.
   The pool wait timer stays open in [#237](https://github.com/exepex/stratum/issues/237).
4. **Body debug** (a live log level per client, masked bodies, a 15-minute auto-off) ships only together with the
   logging starter's timed switch (owner, 2026-11-09). Both are planned
   ([#268](https://github.com/exepex/stratum/issues/268), [#302](https://github.com/exepex/stratum/issues/302)).

## Consequences

- A service that adds the starter and sets nothing gets one WARN per failed call and, with Spring Boot's Actuator or
  the observability starter, the meters; it writes no code.
- **What the redaction does not mask in `url`** (owner, 2026-11-10): a value the redactor finds by its shape is not
  found once Spring percent-encodes a character in it. That covers an email (`@` becomes `%40`), a card number or
  IBAN written with spaces (`%20`), and any of a service's `extra-patterns` that needs such a character; they are
  written as sent. Named secret parameters (`api_key=`, `email=`), JWTs, a URL's user and password, and plain emails,
  cards and IBANs are masked. Issue [#273](https://github.com/exepex/stratum/issues/273) teaches the redactor to read
  percent-escapes. Free text such as `?name=Priya` is logged. The documentation tells teams never to put personal
  data in a URL.
- A value a team joins into a template's path, `uri("/users/" + email)`, is not caught without the redaction; the
  documentation requires `{}` placeholders.
- Without the logging starter there is no cap on the lines: a storm of 4xx answers, which the breaker does not count,
  writes one line per call. With it, its limit of 5000 WARN lines per logger per second applies to each client's
  logger, `stratum.http-client.<client>`.
- A `RestTemplate` call has no template: its line says `uri=none`.
- How the decision weighs (ST-AG-14): per call, one record built after the retries and one enum check, the meters
  from a per-client map made once (speed, throughput); at most one line per failed call and none per refused call
  (log volume); per-client maps bounded by the configured clients (memory); `url` only under redaction, templates
  only in tags (data leaks, security); one class each for the line, the outcome and the meters (maintainability);
  meters per client and outcome, never per URL (scalability).
- Rules ST-SEC-03 and ST-SEC-02 carry the exception for the call log line (owner, 2026-11-09).
