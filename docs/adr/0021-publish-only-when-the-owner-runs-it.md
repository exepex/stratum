# ADR 0021: Publish to Maven Central only when the owner runs it

**Status:** Accepted, 2026-11-12, by the owner.

[← Documentation home](../README.md) · How to use it:
[Publishing](../contributing.md#publishing) · [Using a snapshot](../getting-started/installation.md#using-a-snapshot)

## Context

- [ADR 0020](0020-version-numbers-and-releases.md) states in its Context that the Publish workflow deploys `main`'s
  `-SNAPSHOT` version on each push. The workflow also ran when the owner clicked **Run workflow**, on any branch.
- Maven Central limits each organisation's uploads per calendar month (ADR 0020), and the owner wants to decide
  when anything, a snapshot included, is uploaded.

## Decision

This record replaces ADR 0020's statement that the workflow deploys `main`'s `-SNAPSHOT` on each push.

1. **Nothing is published on a push to `main`.** The Publish workflow runs only for a tag `v<version>` that the owner
   creates, and when the owner clicks **Run workflow** in the Actions tab.
2. **A manual run publishes only from `main` or a `release/*` branch.** There it deploys the `-SNAPSHOT` version in
   `.mvn/maven.config`; on any other branch, or for a version without `-SNAPSHOT`, it publishes nothing.
3. Releases do not change: a tag still uploads a deployment that waits until the owner clicks **Publish** in the
   Central Portal.

## Consequences

- The snapshot in Maven Central's snapshot repository changes only when the owner runs the workflow, so it may lag
  `main`.
- ADR 0020's open question, whether snapshot deploys count toward the monthly limits, now concerns only the runs the
  owner starts.
