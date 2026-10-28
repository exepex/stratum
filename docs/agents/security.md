# Security rules

Part of the agent rules in [AGENTS.md](../../AGENTS.md); every agent follows them. Adapted from the CodeGuard AI
security checklist (`CG-SEC-*`), which follows the OWASP Top 10 (2021). Severities use the one scale defined in
[review](review.md): blocking, should fix, minor.

A security flaw in Stratum is copied into every service that uses it, so the rules in the first section are always
**blocking**: a change that breaks one of them does not merge.

## Always blocking

- **ST-SEC-01 Secrets.** No password, API key, token, private key or connection string with credentials in code,
  configuration, tests, test data or documentation. Services receive secrets from their environment or secret store.
- **ST-SEC-02 Logging.** Never log secrets, tokens, `Authorization` headers, cookies, personal data, or whole request
  and response bodies. The logging starter redacts known sensitive fields, and code must not bypass it.
- **ST-SEC-03 Personal data.** Names, email addresses, phone numbers, postal addresses, government IDs, dates of
  birth, card numbers, IP addresses and precise locations never appear in logs, `toString` output, URLs, query
  strings, metric tags or error responses. A type that holds such data excludes it from `toString`.
- **ST-SEC-04 Injection.** SQL and JPQL use bind parameters, never string concatenation of input. Input never reaches
  `ProcessBuilder`, a file path or a log line without validation; carriage returns and line feeds are removed from
  logged values.
- **ST-SEC-05 Error responses.** Errors use Stratum's standard error response, with a stable code and the
  correlation ID. A response never contains a stack trace, exception message, SQL statement or class name.
- **ST-SEC-06 Tokens.** A JWT is accepted only after its signature, issuer, audience and expiry are verified.
  Unsigned tokens and `alg: none` are rejected. A caller's credentials are passed to another service only when token
  relay is explicitly configured.

## Checked on every change

Each of these is blocking when the change creates a reachable weakness, and should fix when it only removes a layer
of defence.

- **ST-SEC-10 Access control (A01).** Endpoints require authentication unless they are explicitly opened. Every
  `permitAll()` and every disabled CSRF protection has a comment giving the reason. Actuator exposes only `health`
  and `info` by default.
- **ST-SEC-11 Cryptography (A02).** Tokens and identifiers use `SecureRandom`, never `Random` or `Math.random`. MD5,
  SHA-1, DES, RC4 and ECB mode are not used. TLS certificate and hostname verification are never turned off.
- **ST-SEC-12 Insecure design (A04).** Request bodies are validated at the boundary with `@Valid` and constraints. The
  size of bodies, uploads, pages and in-memory collections is limited. Public and expensive endpoints have a rate
  limit, configured through the resilience starter.
- **ST-SEC-13 Misconfiguration (A05).** CORS never combines `*` with credentials. The default configuration does not
  turn on debug logging or extra actuator endpoints.
- **ST-SEC-14 Vulnerable components (A06).** Dependency versions come from the BOM. The dependency scan
  (`mvn verify -Psecurity`) fails on a known vulnerability with a CVSS score of 7.0 or higher. CI does not run
  this scan at present, so run it yourself, with an NVD API key, on any change that adds or upgrades a dependency.
- **ST-SEC-15 Integrity (A08).** No Java native deserialization of untrusted data. XML and YAML parsers that read
  untrusted input have external entities and unsafe types turned off.
- **ST-SEC-16 Security events (A09).** Authentication failures and access denials are logged, without the token, and
  counted in a metric.
- **ST-SEC-17 Outbound requests (A10).** Outbound URLs come from configuration. A URL that must come from input is
  checked against an allow-list first.
- **ST-SEC-18 Trust boundaries.** Correlation and context headers arriving from outside are not trusted as identity.
  Identity comes only from a verified token.

## How to check a change

Follow each untrusted value (request parameter, header, body, downstream response, message) from where it enters
to where it is used (SQL, log, file, process, URL, response). Report a finding only for a path that can actually be
reached, and describe that path in the finding. Static analysis and dependency scans miss most of these rules, so
the security role, the reviewer and the judge check them by reading the change.
