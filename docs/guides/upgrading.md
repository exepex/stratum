# Versioning and upgrades

**Status: Planned.** How Stratum is versioned, what one upgrade changes, and how to upgrade a service
safely.

[← Documentation home](../README.md)

## One version for the whole stack

Without Stratum a service tracks the versions of Spring Boot, Spring Cloud, Resilience4j,
Testcontainers, cloud SDKs, JDBC drivers and more, and every service repeats the same upgrade work.
With Stratum the service tracks **one** version: Stratum's. Each Stratum release is built and tested
against one Spring Boot release and one set of library versions. A service under a company parent
tracks two: the company parent sets the Spring Boot version, and Stratum must support it (see
[Using Stratum with a company framework](company-framework.md)).

## Compatibility

| Stratum | Java | Supported Spring Boot line | Tested with | Spring Framework | Status |
| --- | --- | --- | --- | --- | --- |
| 0.1.x | 25 | 4.1.x | 4.1.2 | 7.x | Planned |

Stratum supports Java 25 and one Spring Boot line per release, nothing older. Your parent POM sets
the Spring Boot version. With `stratum-parent` it is always the tested version. Under a company
parent, check that the version the parent sets is in the supported line before you upgrade either
one. If it is not, Stratum logs one `SX-CORE-1005` warning at startup naming the version found and
the supported line, and starts anyway; it never stops a service over the version
([ADR 0007](../adr/0007-spring-boot-version-warning.md)).

Spring Boot 4.1.2 was the latest stable release on 27 October 2026; 4.2.0 was at release candidate
1. Stratum moves to a new Spring Boot minor release after it is generally available and has had its
first patch release.

## Semantic versioning

Stratum versions are `MAJOR.MINOR.PATCH`:

| Change | Example | What it means for your service |
| --- | --- | --- |
| **Patch** | 1.4.2 → 1.4.3 | Bug and security fixes only. No property, default or API changes. Upgrade any time. |
| **Minor** | 1.4 → 1.5 | New features, new properties, library upgrades (including a Spring Boot minor). Old properties keep working, possibly deprecated with a warning at startup. |
| **Major** | 1.x → 2.0 | May remove deprecated properties or APIs, or move to a new Spring Boot major. The release notes give exact migration steps. |

Before 1.0, minor releases may still contain breaking changes; each one is listed in the release
notes.

## How to upgrade a service

1. **Read the release notes** for every version between yours and the target. Look at "Breaking
   changes" and "Deprecations".
2. **Change the version** in one place: the `<parent>` (or the BOM import). Under a company parent,
   check first that the Spring Boot version it sets is in the new release's supported line.
3. **Build:** `mvn verify`. This runs unit and integration tests against the new versions.
4. **Start the service** and look for `WARN` lines about deprecated properties. Each names its
   replacement.
5. **Deploy to a test environment** and check dashboards: error rate, latency, and that traces still
   connect between services.

> [!TIP]
> Upgrade often and in small steps. Two minor versions at a time is easy; two years at a time is a
> project.

## Deprecated properties

When a property is renamed, the old name keeps working for at least one minor release. At startup
Stratum logs:

```text
WARN  Property 'stratum.http-client.timeout' is deprecated; use 'stratum.http-client.defaults.read-timeout' instead.
```

Your IDE also marks deprecated keys in `application.yml` and `application.properties`, using the
configuration metadata shipped with each starter.

## Security fixes

Security fixes in Stratum or in a managed library are released as patch versions as quickly as
possible, and announced with the affected versions. If you cannot upgrade Stratum right away, the
release notes show which version to override as a temporary fix (see
[Parent POM and BOM](../modules/parent-and-bom.md#the-bom)).

### Open advisories

These are known vulnerabilities in a library Spring Boot manages, where the fix is not yet in any
Spring Boot release, so Stratum cannot ship it. Until it is, set the patched version in your
service. With `stratum-parent`, or any parent that extends `spring-boot-starter-parent`, set Spring
Boot's version property (see
[Overriding one managed version](../modules/parent-and-bom.md#overriding-one-managed-version)), and
remove it when you upgrade to a Stratum release that no longer lists the advisory.
If your service imports `spring-boot-dependencies` as a BOM instead
([company framework](company-framework.md)), the property does not reach it: import the library's
own BOM at the patched version above the Spring Boot import. For the advisory below, that is
`io.opentelemetry:opentelemetry-bom` 1.67.0.

| Advisory | Library | Affected | Set in your service |
| --- | --- | --- | --- |
| [GHSA-ccrj-gw8r-854m](https://osv.dev/GHSA-ccrj-gw8r-854m) (Medium, CVSS 5.3) | `io.opentelemetry:opentelemetry-api` | 1.62.0, from Spring Boot 4.1.2 | `<opentelemetry.version>1.67.0</opentelemetry.version>` |
