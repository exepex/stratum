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

At startup Stratum checks the running Spring Boot version against the supported line, and stops the
service with a message naming both versions when it is outside it. This matters most for a service
whose company parent sets the Spring Boot version.

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
