# Contributing to Stratum

Thank you for helping. Stratum is used by many services, so every change is reviewed for quality,
safety and fit. This page explains how to propose a change and what a new starter must include.

[← Documentation home](README.md)

Agents and humans follow the same rules, in [`AGENTS.md`](../AGENTS.md) and the files it links.

## Before you start

1. **Check for existing work.** Look at open issues, pull requests and branches. Never build a
   second version of something already in progress.
2. **Open an issue first** for anything larger than a fix: a new starter, a new property, a changed
   default. Describe the problem, which services need it, and the proposed behaviour.
3. **Decisions that are hard to undo** (a public API, a default many services rely on) get an
   architecture decision record in `docs/adr/`.

## When does something belong in Stratum?

| Belongs in Stratum | Does not belong |
| --- | --- |
| Needed by two or more services | Needed by one service only |
| A cross-cutting concern (logging, security, clients, data access setup) | Business logic or domain types |
| Has a sensible default that suits most services | Needs a different setup in every service |

## Working on the code

```bash
git checkout -b <short-description>
mvn verify                      # formatting, static analysis, unit and integration tests
sh scripts/check-file-sizes.sh  # no file over its line limit
```

- `mvn spotless:apply` fixes formatting.
- Never skip a test or loosen a quality check to make the build pass; fix the cause.
- Keep a pull request to one change, small enough to review in one sitting.

## Checklist for a new starter

A starter is accepted when it:

- [ ] Owns **one** concern and does not reach into another starter's internals.
- [ ] Works with **zero configuration**, and is safe by default (timeouts, redaction, bounded
      resources).
- [ ] Puts every setting in a typed `@ConfigurationProperties("stratum.<module>")` class with
      validation and Javadoc on each field.
- [ ] Has `stratum.<module>.enabled`, defaulting to `true`.
- [ ] Marks every bean `@ConditionalOnMissingBean`, and offers a customizer where adjusting is
      more common than replacing.
- [ ] Registers auto-configuration in `AutoConfiguration.imports`, with explicit ordering.
- [ ] Keeps wiring in the `autoconfigure` package and behaviour in classes testable without Spring.
- [ ] Has unit tests, auto-configuration tests (`ApplicationContextRunner`) and integration tests.
- [ ] Publishes metrics and propagates trace context where it makes remote calls.
- [ ] Has a documentation page in `docs/modules/` in the standard layout: what it does, add it, what
      you get, properties (type, default, description), examples in **both YAML and properties**,
      customize, switch off, troubleshooting.
- [ ] Is listed in the [README](../README.md#modules), the [docs index](README.md) and the
      [configuration reference](configuration/reference.md).

## Documentation rules

- A change to behaviour, a default or a property updates its documentation page **in the same pull
  request**.
- Describe each thing in exactly one place, and link to it from elsewhere.
- Every configuration example is shown in YAML and in properties.
- Mark anything not built yet as **Planned**.
- Keep text files under 500 lines; split by topic and link from an index.
- Write plain, short sentences that a developer new to Spring Boot can follow. Explain any term
  they may not know, or link to the [glossary](getting-started/concepts.md#glossary).
