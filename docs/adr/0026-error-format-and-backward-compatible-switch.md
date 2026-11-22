# ADR 0026: One error format, and one backward-compatible switch, off by default

**Status:** Accepted, 2026-11-22, by the owner. Its decisions were taken from 2026-11-02 to 2026-11-19; each
gives its date.

[← Documentation home](../README.md) · How to use it:
[Backward-compatible mode](../modules/web-compatible-mode.md) ·
[The error format](../modules/web.md#the-error-format) ·
[Migrating a service](../guides/migrating-a-service.md)

## Context

- **Stratum's goal is one standard for every service:** every error is
  [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) with a code, a severity, the trace ID and the
  correlation ID, and every request has a time limit and a body size limit.
- **Most services that adopt Stratum are already running and have consumers.** Those consumers read Spring Boot's
  error body today, and some of their requests take longer than 10 seconds or send bodies larger than 256KB. A
  consumer can take months to change. If adding the starter changed the error body, or started answering `503` and
  `413`, the service would break its contract with its consumers on the day it adopted Stratum.
- **Other modules have contracts with consumers too,** such as the data module's query timeouts and the planned
  Kafka starter's dead-letter headers. A switch per module would add keys that every service has to set, and
  ST-ENG-34 keeps the number of keys low.
- **No service ran on Stratum when the default was chosen,** so the default protects new services. A running service
  keeps its contract with its consumers (ST-ENG-28) by setting `stratum.backward-compatible=true` in its first
  release on Stratum.

## Decision

1. **The standard error format is RFC 9457 Problem Details** (owner, 2026-11-02), as described in
   [The error format](../modules/web.md#the-error-format), on both web stacks.
2. **One global compatibility switch** (owner, 2026-11-03). `stratum.backward-compatible` lives in `stratum-core`
   and defaults to `false`, strict mode. It protects external contracts only: bodies, statuses, headers, message
   formats and the SLA, as consumers see them. Everything internal always follows Stratum's defaults.
3. **Each setting is either external or internal** (owner, 2026-11-03):

   | Setting | Class | When it applies |
   |---|---|---|
   | Query timeout (30s) and transaction timeout (60s) | external | strict mode only |
   | `open-in-view=false` | external | strict mode only |
   | `hibernate.query.fail_on_pagination_over_collection_fetch=true` | external | strict mode only |
   | Web error body and web limits; DB error mapping | external | strict mode only |
   | Kafka dead-letter headers (planned, with the Kafka starter) | external | strict mode only |
   | Clean SQL exceptions (JDBC boundary) | internal | always |
   | Logs, spans, redaction, metrics | internal | always |
   | 3s pool wait (`connection-timeout`) | internal | always (a fast, visible failure protects the service) |
   | `ddl-auto=validate` | internal | always |
   | Leak alarm | internal | off: its cost is above the limit of 1 µs per borrow; HikariCP's key turns it on |
   | `hibernate.jdbc.log.errors=false`, `hibernate.jdbc.log.warnings=false` | internal | always |

4. **What the web module gives in backward-compatible mode** (owner, 2026-11-02, under the key of 2026-11-03):
   - Spring Boot's own error body for every error that Spring or Spring Boot answers, holding what the service's
     `spring.web.error.include-*` settings say;
   - Stratum's body for errors that no consumer has relied on before: a `StratumException`, a timeout and a body too
     large;
   - Stratum's body for an exception that implements `FailureWithErrorCode`, which can be one its consumers already
     know (decision 7);
   - Stratum's body for any request that sends `Accept: application/problem+json`;
   - no default time limit, no default body size limit and, on WebFlux, no default multipart limit; these are the
     written exception to rules ST-ENG-25 and ST-ENG-52;
   - the `X-Correlation-Id` header on every response, and `traceId` and `correlationId` added to a `ProblemDetail`
     the service's own handler returns.
5. **In strict mode, the service gets the standard** (owner, 2026-11-02): every error has Stratum's body, every
   request has 10 seconds until its response starts and a body of up to 256KB, and a WebFlux multipart upload may use
   10MB of disk per part and have 50 parts. Every limit the service sets applies in both modes.
6. **The migration path** (owner, 2026-11-02). Each consumer moves to the standard body when it is ready, by sending
   `Accept: application/problem+json`. The counter `stratum.web.requests.without.standard.errors` shows which routes
   still answer with Spring Boot's body. Once it stays at 0, the service sets `false`. That is
   each service's end state. A new service leaves the default.
7. **Marking an existing exception changes its answer** (owner, 2026-11-02). An exception that implements
   `FailureWithErrorCode` gets Stratum's body even in backward-compatible mode, and its status comes only from
   `stratum.web.error.statuses`: an `@ResponseStatus` on it is ignored, and a code with no status gets `500`. A team
   maps the code to the status the exception has today before it marks the exception, and tells its consumers about
   the new body.
8. **Every code's status is set in `stratum.web.error.statuses`,** Stratum's own codes included (owner,
   2026-11-19). The status of `SX-WEB-1007`, a request that ran out of its time limit, is `503` when the service sets
   none; there is no `stratum.web.timeout.status`. The defaults of the HTTP client's codes are in
   [ADR 0031](0031-http-client-errors-and-body-limits.md).
9. **Stack traces are a security leak** (owner, 2026-11-02). While the mode is on, Stratum logs one WARN line at every
   start when `spring.web.error.include-stacktrace` or `spring.web.error.include-exception` shows them. The line
   cannot be turned off; a team that accepts the risk handles it in its monitoring.
10. **The mode makes no other promise** (owner, 2026-11-03). It is one switch and gets no switch inside it. It does
    not list what a new feature may or may not do in the mode.

## Consequences

- A new service adds a starter and gets each module in strict mode, without writing a line.
- A running service that adopts Stratum sets `stratum.backward-compatible=true`, and then follows decision 6 to
  `false`. Adding the web starter then breaks no consumer, apart from the two status fixes that make both stacks
  answer the same, listed in [Backward-compatible mode](../modules/web-compatible-mode.md#error-bodies).
- The internal settings apply in both modes. A running service gets them, such as the 3s pool wait and
  `ddl-auto=validate`, on the day it adopts the starter.
- "One standard" is reached service by service. Until a service sets `false`, its errors and limits are not yet the
  standard, and the startup line says so.
- A backward-compatible service has no protection against slow or large requests until it sets limits. The
  documentation tells teams to give each new endpoint its own limit.
- How the decision weighs (ST-AG-14): the default protects services, because strict mode keeps every timeout and
  limit (ST-ENG-25, ST-ENG-52) and redaction always applies. No request path depends on the switch beyond the
  settings in decision 3.
- Rules ST-ENG-25 and ST-ENG-52 carry the decision into the code.
