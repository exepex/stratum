# ADR 0007: Warn when Spring Boot is outside the supported line

**Status:** Accepted, 2026-11-01, by the owner. Amends
[ADR 0002](0002-no-startup-version-check.md): Stratum still never stops startup over the Spring Boot
version, but now logs one warning. It also settles the part of
[ADR 0001](0001-coexistence-with-company-frameworks.md) decision 1 that ADR 0002 replaced. Every
other part of both stands.

[← Documentation home](../README.md) · How to use it:
[Versioning and upgrades](../guides/upgrading.md#compatibility) ·
[Error codes](../guides/error-codes.md#stratum-core)

## Context

ADR 0002 removed the startup check because the parent POM already decides the Spring Boot version,
and a check that stops startup would repeat that decision in code.

Under a company parent (setup B of ADR 0001), `stratum-parent` is not used, so no build step
compares the company's Spring Boot version with the line Stratum supports. A version outside that
line fails later, with errors such as `NoSuchMethodError`, that do not point at the cause.

## Decision

1. Each Stratum release supports one Spring Boot line, such as `4.1.x`, and Java 25. Older lines are
   not supported.
2. At startup, `stratum-core` compares the running Spring Boot version with that line. When it is
   outside, it logs one warning, `SX-CORE-1005`, naming the version found, the supported line and
   where to change it: the parent POM. Any patch release of the line is quiet.
3. The warning never stops startup. The parent POM still decides the version, as ADR 0002 says.
4. The supported line comes from the build: the root `pom.xml`'s Spring Boot parent is written into
   `stratum-core`'s jar, so the version stays in one place (ADR 0005).
5. When Spring Boot cannot report its version, as from some repackaged jars, nothing is logged.

## Consequences

- A team under a company parent learns of an unsupported version from one line at startup, before
  anything fails.
- A team that runs a newer line on purpose sees the warning on every start until a Stratum release
  supports that line.
- Java needs no check: Stratum's classes are compiled for Java 25, so an older JVM refuses to load
  them.
