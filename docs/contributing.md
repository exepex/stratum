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

## Checks on every pull request

GitHub Actions builds every pull request, and every push to `main`, with
[`.github/workflows/build.yml`](../.github/workflows/build.yml). It runs one command on JDK 25:

```bash
mvn verify -Psecurity org.cyclonedx:cyclonedx-maven-plugin:<version>:makeAggregateBom
```

| Part | What it does | Where to find the result |
| --- | --- | --- |
| `verify` | Every check that `mvn verify` runs on your machine | The run's log |
| `-Psecurity` | Scans the dependencies with OWASP Dependency-Check and fails the run on a vulnerability scored 7.0 or higher | The `dependency-check-reports` artifact |
| `makeAggregateBom` | Writes an SBOM (software bill of materials): a CycloneDX list of every library the build includes | The `sbom` artifact, as `bom.json` and `bom.xml` |

An artifact is a file that a run keeps; open the run in the **Actions** tab and download it from
the run's summary page. The SBOM is written only when the build and the scan pass. The SBOM
plugin's version is not written in the workflow: it is the version Spring Boot's parent manages,
which the workflow reads from `stratum-parent` before the build.

The scan downloads vulnerability data from the US National Vulnerability Database (NVD), which needs
an API key. The run stops with an error naming the missing key until the repository has a secret named
`NVD_API_KEY`. Request a free key from the [NVD](https://nvd.nist.gov/developers/request-an-api-key),
then add it under **Settings → Secrets and variables → Actions** in the repository. Never write the
key in a file.

GitHub gives no repository secrets to a pull request from a fork, so such a pull request is built
and gets its SBOM, but skips the scan; the run shows a warning that says so. The scan runs on `main`
when the pull request merges.

## Rules for a new starter

A new starter meets every rule in [engineering](agents/engineering.md), [testing](agents/testing.md),
[security](agents/security.md) and [documentation](agents/documentation.md).
