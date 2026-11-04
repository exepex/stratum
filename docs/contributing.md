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
it needs, use `./mvnw -pl starters/stratum-starter-webmvc -am verify`.

Stratum's version is set once, as `revision` in `.mvn/maven.config`. To change it, edit that line.

`.mvn/maven.config` also sets `-T 1C`, so Maven builds modules that do not depend on each other at
the same time, one thread per CPU core. When a build fails, lines from several modules are mixed in
the log; the Reactor Summary at the end names the module that failed. To build one module at a
time, add `-T 1`.

### Benchmarking the JDBC boundary

The data starter's `benchmark` profile measures what the [JDBC boundary](modules/data-jpa.md#clean-database-errors)
adds to each JDBC call, with [JMH](https://github.com/openjdk/jmh). It needs Docker, because each
JMH fork starts its own PostgreSQL container, and it takes about 10 to 15 minutes:

```bash
./mvnw -pl starters/stratum-starter-data-jpa -am -Pbenchmark clean integration-test
```

The command starts with `clean`, because test classes an earlier build compiled count as up to date,
and the compiler would then skip JMH's generator. The run builds the module and runs its tests first. JMH then compares the raw connection pool with
the boundary on the same calls, and writes its result to
`starters/stratum-starter-data-jpa/target/jmh-result.json`. A plain build never runs it.

The run leaves JMH's generated sources in the module's `target` folder, where PMD would check them.
So run `./mvnw clean verify`, with `clean`, as the next build.

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

Until that Spring Boot release exists, the owner can let the scan skip the advisory: an entry in
[`osv-scanner.toml`](../osv-scanner.toml) with the advisory ID, the reason and an expiry date at
most 30 days away, after which the scan fails again. The same pull request lists the advisory and
the version services should set under
[Open advisories](guides/upgrading.md#open-advisories). The entry is deleted when the Spring Boot
upgrade that fixes it merges.

GitHub's Dependabot alerts cover what a pull request scan cannot: a vulnerability published after a
library is already on `main`. GitHub reads the dependency graph and opens an alert in the
repository's **Security** tab. Alerts are a repository setting, not a file in the repository, and
they never block a pull request.

Dependabot also opens pull requests that upgrade dependencies every week. They run these same
checks; how they are grouped and reviewed is in
[ADR 0004](adr/0004-dependency-updates.md). A security update, the pull request Dependabot opens
from an alert, comes only once Dependabot security updates are switched on in the repository
settings. The Maven plugins whose versions Spring Boot sets, such as Compiler and Surefire, move only
with a Spring Boot upgrade, so a fix to one of them also arrives only through that upgrade.

The `security` profile (OWASP Dependency-Check) still works when you run it yourself with an NVD
API key; see [Optional profiles](modules/parent-and-bom.md#optional-profiles). CI does not run it,
because its download from the US National Vulnerability Database (NVD) took from 24 minutes to over
an hour.

## The wiki

The [GitHub wiki](https://github.com/exepex/stratum/wiki) shows these pages to teams that read
Stratum's documentation there. It is a copy: `docs/` stays the one source, so edit a page in
`docs/`, never in the wiki. The wiki's own pages, such as audit reports, are written in the wiki.

[`.github/workflows/wiki.yml`](../.github/workflows/wiki.yml) copies the pages after every merge to
`main` that changes `docs/`, a starter's `README.md` or the sync itself, and after every edit in the
wiki, so a new audit report reaches the sidebar. It runs
[`.github/wiki/WikiSync.java`](../.github/wiki/WikiSync.java), which:

- copies [`docs/README.md`](README.md) as the wiki's **Home**, and every page it links except the
  project README; the agent rules in `docs/agents/` stay in the repository;
- names each page from its path, such as `Modules-web` for `docs/modules/web.md` and
  `stratum-starter-logging` for that starter's `README.md`;
- builds the sidebar from the tables in `docs/README.md`, in the groups Getting started,
  Configuration, Guides, Modules and Decisions (ADRs), followed by Audit reports;
- turns each link to a copied page into a wiki link, and each link to any other file into its
  address on GitHub;
- marks every page it writes. A page without the mark was written in the wiki: the sync never
  changes or deletes it, and lists it under Audit reports. The one exception is the wiki's first,
  hand-made Home, which `docs/README.md` replaces.

The sync checks everything before it writes. It stops and leaves the wiki as it was when a link
leads out of the repository or to a missing file, when a page shows a relative image, when two
pages get the same name, when a page written in the wiki has a copied page's name, or when
`docs/README.md` has a section with no sidebar group. So a new section in `docs/README.md` also
needs its group in `WikiSync.java`, and a page written in the wiki needs a name no copied page has.
A run that changes no page makes no commit.

On a pull request that touches these files, the workflow runs the sync's golden test,
[`.github/wiki/test.sh`](../.github/wiki/test.sh), then syncs into a throwaway copy of the wiki and
lists the pages that would change on the run's summary page. It pushes nothing. To run the test
yourself, run `.github/wiki/test.sh`; it needs a JDK and git.

## Rules for a new starter

A new starter meets every rule in [engineering](agents/engineering.md), [testing](agents/testing.md),
[security](agents/security.md) and [documentation](agents/documentation.md).
