# Contributing to Stratum

Thank you for helping. Stratum is used by many services, so every change is reviewed for quality,
safety and fit. This page explains how to propose a change.

[← Documentation home](README.md)

Agents and humans follow the same rules. They are written once, in [`AGENTS.md`](../AGENTS.md) and
the files it links, and this page does not repeat them. Read them before your first change.

## Before you start

**Open an issue first** for anything larger than a fix: a new starter, a new property, a changed
default. Describe the problem, which services need it, and the proposed behaviour.

## When does something belong in Stratum?

| Belongs in Stratum | Does not belong |
| --- | --- |
| Needed by two or more services | Needed by one service only |
| A cross-cutting concern (logging, security, clients, data access setup) | Business logic or domain types |
| Has a sensible default that suits most services | Needs a different setup in every service |

## Working on the code

```bash
git checkout -b <short-description>
./mvnw spotless:apply   # format the code
./mvnw verify           # formatting, static analysis, unit and integration tests
```

Use the Maven Wrapper (`./mvnw`, or `mvnw.cmd` on Windows): it runs the Maven version the build is
tested with. Run whole phases up to at least `package` from the repository root, such as
`./mvnw verify`. The Checkstyle, PMD and Enforcer rules are read from the `stratum-build-rules` jar,
which a shorter run, such as `./mvnw validate`, does not build. To build one module and the modules
it needs, use `./mvnw -pl starters/stratum-starter-web -am verify`.

Stratum's version is set once, as `revision` in `.mvn/maven.config`. To change it, edit that line.

Then open a pull request. Its description and review follow
[`AGENTS.md`](../AGENTS.md#git-and-pull-requests).

## Rules for a new starter

A new starter meets every rule in [engineering](agents/engineering.md), [testing](agents/testing.md),
[security](agents/security.md) and [documentation](agents/documentation.md).
