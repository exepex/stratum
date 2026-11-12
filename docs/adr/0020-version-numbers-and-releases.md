# ADR 0020: Version numbers and releases

**Status:** Accepted, 2026-11-12, by the owner.

[← Documentation home](../README.md) · How to use it:
[Versioning and upgrades](../guides/upgrading.md) · [Publishing](../contributing.md#publishing)

## Context

- Stratum 0.1.0 is its first release, published to Maven Central on 2026-11-11 from the tag `v0.1.0`
  ([release notes](../releases/0.1.0.md)). Before it, no rule said what a later version number promises.
- Stratum's API is its `stratum.*` keys, their defaults and every public type without `@StratumInternal`
  ([ADR 0009](0009-public-api-and-no-package-cycles.md)). What each kind of version means for a service is described
  in [Versioning and upgrades](../guides/upgrading.md#semantic-versioning).
- The [Publish workflow](../contributing.md#publishing) deploys `main`'s `-SNAPSHOT` version on each push, and
  uploads a release for each tag `v<version>`, which waits until the owner clicks **Publish** in the Central Portal.
  It checks only that the tag holds no `SNAPSHOT`. The Build workflow runs on pull requests and on pushes to `main`.
- Maven Central limits each organisation's releases per calendar month, by number of files (signatures and checksums
  included), size and number of releases. Exceeding one starts a 30-day grace period. Sonatype publishes no fixed
  numbers: its Usage Center holds them, and its table of the top 10% of publishers shows about 1,167 files, 78 MB and
  7 releases a month ([Sonatype: Maven Central Publishing Limits](https://central.sonatype.org/publish/maven-central-publishing-limits/),
  read 2026-11-12). Release 0.1.0 is 444 files and 4.6 MB on Central, so files limit Stratum first.

## Decision

1. **Semantic versions, with the rules for below 1.0 in the upgrade guide.** A minor release may change Stratum's
   API, and a patch release never does: it fixes bugs and security issues, with no change to a key, a default or an
   API type. A type with `@StratumInternal` may change in any release. Each breaking change has a migration note in
   the release notes of the version that ships it (ST-DOC-10).
2. **`main` carries the next minor's `-SNAPSHOT`.** Right after a release tag, a pull request moves `revision` in
   `.mvn/maven.config` to the next minor, such as `0.2.0-SNAPSHOT` after `v0.1.0`.
3. **Fix forward.** A fix ships in the next release from `main`. Only a security fix that cannot wait is released as
   a patch, from a branch `release/0.<minor>.x` that the owner creates from the release tag. The fix reaches that
   branch through a reviewed pull request, whose Build must pass, and goes to `main` as well.
4. **The owner tags and releases.** The owner creates each tag and its GitHub release, links the release notes in
   it, and clicks **Publish** in the Central Portal.
5. **At most one planned release a calendar month.** This is a policy, not a Sonatype number: it keeps the second
   release a month free for a security patch. Before each tag, the owner checks the Central Portal's Usage Center.

## Consequences

- A service on a released version upgrades within a minor line without changes, and reads the migration notes for a
  new minor.
- A service on a snapshot moves to the new `-SNAPSHOT` after each release; an old snapshot stops changing and the
  snapshot repository deletes it after 90 days.
- No maintenance branch exists until an urgent security fix needs one.
- Whether snapshot deploys count toward the monthly limits is not known; the owner checks it in the Usage Center.
