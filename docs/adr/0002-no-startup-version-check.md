# ADR 0002: No startup check of the Spring Boot version

**Status:** Accepted, 2026-10-28, by the owner. Replaces one part of
[ADR 0001](0001-coexistence-with-company-frameworks.md): the startup version check in its decision 1.

[← Documentation home](../README.md)

## Context

ADR 0001 decided that Stratum stops a service at startup when the running Spring Boot version is
outside the line the Stratum release supports. Nothing built that check yet.

The Spring Boot version of a service is already decided in one place: its parent POM. With
`stratum-parent` it is the version Stratum is tested with. Under a company parent it is the
company's choice, made in the company parent's POM. A runtime check would repeat that decision in
code, and would stop a service that the team chose to run on a newer patch or minor version.

## Decision

Stratum does not check the Spring Boot version at startup. The parent POM alone decides it. Each
release still states the Spring Boot line it supports in the
[compatibility table](../guides/upgrading.md#compatibility).

Every other part of ADR 0001 stands.

## Consequences

- `stratum-core` has no version check, and rule ST-ENG-65 is removed.
- A service under a company parent on an unsupported Spring Boot version is not stopped. It may fail
  at startup or at run time with errors such as `NoSuchMethodError`. Its team compares the parent's
  Spring Boot version with the compatibility table before an upgrade.
