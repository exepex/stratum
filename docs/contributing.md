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
./mvnw verify           # formatting, static analysis, tests and the 80% coverage gate
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
[`.github/workflows/build.yml`](../.github/workflows/build.yml). It runs one Maven command on
JDK 25, then scans the result:

```bash
./mvnw verify org.cyclonedx:cyclonedx-maven-plugin:<version>:makeAggregateBom
```

| Part | What it does | Where to find the result |
| --- | --- | --- |
| `verify` | Every check that `./mvnw verify` runs on your machine | The run's log |
| `makeAggregateBom` | Writes an SBOM (software bill of materials): a CycloneDX list of every library the build includes | The `sbom` artifact, as `bom.json` and `bom.xml` |
| OSV-Scanner | Looks up every library in `bom.json` in the [OSV.dev](https://osv.dev) vulnerability database and fails the run if any has a known vulnerability | The log of the step "Scan the SBOM for known vulnerabilities" |

An artifact is a file that a run keeps; open the run in the **Actions** tab and download it from
the run's summary page. The SBOM is written only when the build passes, and it is uploaded before
the scan, so it is kept when the scan fails. The SBOM plugin's version is not written in the
workflow: it is the version Spring Boot's parent manages, which the workflow reads from
`stratum-parent` before the build.

OSV-Scanner sends only the names and versions of the libraries to OSV.dev, and needs no account or
key. It fails on any known vulnerability, whatever its score, because OSV-Scanner has no score
threshold. When a scan fails, upgrade the library. A library whose version Spring Boot manages is
fixed by a Spring Boot upgrade, not by an override in Stratum
([ST-ENG-61](agents/engineering.md#dependencies)).

GitHub's Dependabot alerts cover what a pull request scan cannot: a vulnerability published after a
library is already on `main`. GitHub reads the dependency graph and opens an alert in the
repository's **Security** tab. Alerts are a repository setting, not a file in the repository, and
they never block a pull request.

Dependabot also opens pull requests that upgrade dependencies every week. They run these same
checks; how they are grouped and reviewed is in
[ADR 0004](adr/0004-dependency-updates.md). A security update, the pull request Dependabot opens
from an alert, comes only once Dependabot security updates are switched on in the repository
settings. The eight Maven plugins that ADR 0004 leaves out move only with a Spring Boot upgrade, so
a fix to one of them also arrives only through that upgrade.

The `security` profile (OWASP Dependency-Check) still works when you run it yourself with an NVD
API key; see [Optional profiles](modules/parent-and-bom.md#optional-profiles). CI does not run it,
because its download from the US National Vulnerability Database (NVD) took from 24 minutes to over
an hour.

## Rules for a new starter

A new starter meets every rule in [engineering](agents/engineering.md), [testing](agents/testing.md),
[security](agents/security.md) and [documentation](agents/documentation.md).
