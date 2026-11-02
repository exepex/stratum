# ADR 0008: One error format, with a backward-compatible mode on by default

**Status:** Accepted, 2026-11-02, by the owner. Records the error format the web starters already
use and the backward-compatible mode built in `stratum.web.backward-compatible`. It qualifies the
safe defaults of rules ST-ENG-25 and ST-ENG-52 while the mode is on.

[← Documentation home](../README.md) · How to use it:
[Backward-compatible mode](../modules/web-compatible-mode.md) ·
[The error format](../modules/web.md#the-error-format) ·
[Migrating a service](../guides/migrating-a-service.md)

## Context

Stratum's goal is one standard for every service: every error is
[RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) with a code, a severity, the
trace ID and the correlation ID, and every request has a time limit and a body size limit.

Most services that adopt Stratum are already running and have consumers. Those consumers read
Spring Boot's error body today, and some of their requests take longer than 10 seconds or send
bodies larger than 256KB. A consumer can take months to change. If adding the starter changed the
error body, or started answering `503` and `413`, the service would break its contract with its
consumers on the day it adopted Stratum.

## Decision

1. **The standard error format is RFC 9457 Problem Details**, as described in
   [The error format](../modules/web.md#the-error-format), on both web stacks.
2. **`stratum.web.backward-compatible` is `true` by default.** With only
   `stratum.web.enabled=true`, a service gets:
   - Spring Boot's own error body for every error that Spring or Spring Boot answers, holding what
     the service's `spring.web.error.include-*` settings say;
   - Stratum's body for errors that no consumer has relied on before: a `StratumException`, a
     timeout and a body too large;
   - Stratum's body for an exception that implements `FailureWithErrorCode`, which can be one its
     consumers already know (decision 5);
   - Stratum's body for any request that sends `Accept: application/problem+json`;
   - no default time limit, no default body size limit and, on WebFlux, no default multipart limit;
   - the `X-Correlation-Id` header on every response, and `traceId` and `correlationId` added to a
     `ProblemDetail` the service's own handler returns.
3. **With `false`, the service gets the standard**: every error has Stratum's body, every request
   has 10 seconds until its response starts and a body of up to 256KB, and a WebFlux multipart
   upload may use 10MB of disk per part and have 50 parts. Every limit the service sets applies in
   both modes.
4. **The migration path.** Each consumer moves to the standard body when it is ready, by sending
   `Accept: application/problem+json`. The counter `stratum.web.requests.without.standard.errors`
   shows which routes still answer with Spring Boot's body. Once it stays at 0, the service sets
   `false`. That is each service's end state. A new service has no consumers, so it sets `false`
   from its first release.
5. **Marking an existing exception changes its answer.** An exception that implements
   `FailureWithErrorCode` gets Stratum's body even in backward-compatible mode, and its status comes
   only from `stratum.web.error.statuses`: an `@ResponseStatus` on it is ignored, and a code with no
   status gets `500`. A team maps the code to the status the exception has today before it marks the
   exception, and tells its consumers about the new body.
6. **Stack traces are a security leak.** While the mode is on, Stratum logs one WARN line at every
   start when `spring.web.error.include-stacktrace` or `spring.web.error.include-exception` shows
   them. The line cannot be turned off; a team that accepts the risk handles it in its monitoring.
7. **The mode is permanent and frozen.** The property is never removed, not even in a major version,
   because organisations adopt Stratum late and need it. Frozen means:
   - while the mode is on, a new Stratum feature must not change the status or the body of an
     existing response, and must not refuse or cut off a request that worked before, unless the
     service turns the feature on;
   - additions are allowed in both modes, such as a new response header, a new log field, or IDs
     added to the service's own error body;
   - Spring MVC and WebFlux always behave the same, so a fix that makes one stack match the other
     applies in both modes;
   - the mode itself never gets new switches.
8. **The safe-defaults rules have a written exception.** ST-ENG-25 and ST-ENG-52 require timeouts
   and limits with no configuration. While the mode is on, the web module sets no default time or
   body size limit, and both rules say so.

## Consequences

- Adding the web starter to a running service breaks no consumer, apart from the two status fixes
  that make both stacks answer the same, listed in
  [Backward-compatible mode](../modules/web-compatible-mode.md#error-bodies).
- "One standard" is reached service by service, not on the day the starter is added. Until a
  service sets `false`, its errors and limits are not yet the standard, and the startup line says
  so.
- A backward-compatible service has no protection against slow or large requests until it sets
  limits. The documentation tells teams to give each new endpoint its own limit.
- Every new web feature is checked against decision 7 before it is built.
