# ADR 0018: The Maven groupId is `io.github.exepex.stratum`; Java packages stay under `com.exepex.stratum`

**Status:** Accepted, 2026-11-10, by the owner.

[← Documentation home](../README.md) · How to use it:
[Installation](../getting-started/installation.md) · [Parent and BOM](../modules/parent-and-bom.md)

## Context

- Stratum's artifacts used the groupId `com.exepex.stratum`. Maven Central publishes under a namespace only after its
  owner proves control of it; for a `com.<name>` namespace that proof is a DNS TXT record on the domain
  ([Sonatype: Register a namespace](https://central.sonatype.org/register/namespace/), read 2026-11-10).
- The domain `exepex.com` is lost. Stratum can no longer prove the `com.exepex` namespace, and whoever registers the
  domain next could prove it and publish artifacts under it. A service that trusts `com.exepex.stratum` could then
  download code Stratum never wrote: a supply-chain risk.
- The repository belongs to the GitHub organisation `exepex`. Maven Central verifies an `io.github.<name>` namespace
  through the GitHub account instead of a domain. For an organisation, the namespace is not created automatically:
  the owner proves control by creating a temporary public repository in `exepex` named after the verification key
  that Central gives (same source).
- Nothing has been released, so no service depends on the old coordinates.

## Decision

1. **Every Stratum artifact has the groupId `io.github.exepex.stratum`**: the parents, the BOM, the build rules, the
   libraries, the starters and the bundles. The artifact IDs and the version stay as they are.
2. **The Java packages stay under `com.exepex.stratum`.** Maven Central does not require a package to match its
   groupId, and the package names are already in every class, `AutoConfiguration.imports`, `spring.factories` key,
   logger name and documented customizer. Moving them would change the public API for no gain.
3. Every POM and every documented Maven example uses the new groupId; text that names a Java package, class or logger
   keeps `com.exepex.stratum`.

## Consequences

- A service declares `io.github.exepex.stratum` in its `<parent>`, its BOM import and its dependencies, and still
  imports classes from `com.exepex.stratum`.
- The groupId and the packages differ. Each document that shows a coordinate uses the groupId; each that shows a
  class or a key in `spring.factories` uses the package.
- Before the first release, the owner verifies the `io.github.exepex` namespace on Maven Central, as the Context says.
