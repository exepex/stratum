# Security rules

Mandatory for every change to Stratum code. Index and summary: [AGENTS.md](../../AGENTS.md). Adapted from the
CodeGuard AI security checklist (`CG-SEC-*`, OWASP Top 10 2021). A Stratum flaw is copied into every service, so a
finding on a changed line marked **BLOCKER** below always stops the merge.

## Always BLOCKER

- **ST-SEC-01 Secrets.** No password, API key, token, private key or connection string with credentials in code,
  config, tests, fixtures or docs. Secrets reach a service through its environment or secret store, never a literal.
- **ST-SEC-02 Logging.** Never log secrets, tokens, `Authorization` headers, cookies, personal data or full request
  and response bodies. The logging starter redacts known sensitive fields; code must not route around it.
- **ST-SEC-03 Personal data.** No personal data (name, email, phone, address, government ID, date of birth, card
  number, IP address, precise location) in logs, `toString`, URLs, query strings, metric tags or error responses.
  Types holding it exclude it from `toString` (no `@Data`/`@ToString` without `@ToString.Exclude`).
- **ST-SEC-04 Injection.** SQL and JPQL use bind parameters only, never string concatenation of input. No input
  reaches `ProcessBuilder`, a file path or a log line without validation (strip CR/LF from logged values).
- **ST-SEC-05 Errors.** Responses use the standard error envelope with a stable code and the correlation ID. Never a
  stack trace, exception message, SQL or class name in a response.
- **ST-SEC-06 Tokens.** JWTs are verified for signature, issuer, audience and expiry. Never accept unsigned tokens or
  `alg: none`. Never forward a caller's credentials to a third party unless token relay is explicitly configured.

## Checked on every change

- **ST-SEC-10 Access control (A01).** Secure by default: endpoints require authentication unless explicitly opened,
  and every `permitAll()` or disabled CSRF has a documented reason. Actuator exposes only health and info by
  default.
- **ST-SEC-11 Cryptography (A02).** `SecureRandom` for tokens and identifiers, never `Random` or `Math.random`. No
  MD5, SHA-1, DES, RC4 or ECB mode. TLS verification is never disabled; no trust-all managers.
- **ST-SEC-12 Insecure design (A04).** Request DTOs are validated (`@Valid` plus constraints) at the boundary. Sizes of
  bodies, uploads, pages and in-memory collections are bounded.
- **ST-SEC-13 Misconfiguration (A05).** No CORS `*` with credentials. No debug logging or extra actuator endpoints
  in default configuration.
- **ST-SEC-14 Vulnerable components (A06).** Dependencies are version-managed by the BOM. The security scan
  (OWASP Dependency-Check or equivalent, once in the build) must pass; a known high-severity CVE blocks the release.
- **ST-SEC-15 Integrity (A08).** No Java native deserialization of untrusted data. XML and YAML parsers reading
  untrusted input disable external entities and unsafe types.
- **ST-SEC-16 Security logging (A09).** Authentication failures and access denials are logged (without the token)
  and counted in a metric.
- **ST-SEC-17 SSRF (A10).** Outbound URLs come from configuration, not from request input. If a URL must come from
  input, validate it against an allow-list.
- **ST-SEC-18 Boundary trust.** Inbound correlation and context headers from outside the trust boundary are not
  trusted as identity. Identity comes from a verified token only.

## How to check

Trace each untrusted value (request parameter, header, body, downstream response, message payload) from where it
enters to where it is used (SQL, log, file, process, URL, response). Only a reachable path is a finding; say which
path in the finding. When the build has them, the static analysis and dependency scans are gates, but they miss
most of the rules above, so the reviewer and the judge check them by reading the change.
