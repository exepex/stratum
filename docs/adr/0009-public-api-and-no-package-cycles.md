# ADR 0009: Mark internal types with `@StratumInternal`, and keep packages free of cycles

**Status:** Accepted, 2026-11-02, by the owner.

[← Documentation home](../README.md) · How to use it:
[Versioning and upgrades](../guides/upgrading.md#public-api) ·
[Engineering rules ST-ENG-56 and ST-ENG-57](../agents/engineering.md#public-api-and-packages)

## Context

Before 0.1.0, every published module needs a clear line between what services may use, which
semantic versioning protects, and what only Stratum's own modules use, which can change in any
release. Today almost every class is `public`, so a service cannot tell the two apart.

The first plan was to move internal classes into `.internal` packages. That creates package cycles:
in `stratum-web`, the API class `ErrorResponseFactory` uses `WebErrorCode`, which would move to
`web.internal`, while `BackwardCompatibleModeReport`, which would move there too, uses the API class
`WebProperties`. The two packages would depend on each other. The same happens in `stratum-core`
and the starters. Stratum's ArchUnit tests reject every package cycle (ST-TEST-21), and that check
stays.

The options were:

- `module-info.java`: Spring Boot services run on the class path, where module exports are ignored,
  so it protects nothing;
- API Guardian's `@API(status = INTERNAL)`: works, but adds a dependency for one annotation;
- `.internal` packages, with more classes split or moved until no cycle is left: a large refactoring
  for a label;
- `.internal` packages with a looser cycle check: weakens a safeguard (ST-AG-07).

## Decision

1. **`stratum-core` has one annotation, `com.exepex.stratum.core.api.StratumInternal`.** It goes on
   every public type, in every published module, that services must not use. A service must not use
   a type that carries it: such a type can change or go away in any release.
2. **A public type without the label is API.** A type is API when a service uses it: it implements or
   extends it, calls it, puts it on its own code, or declares a bean of its type to replace Stratum's,
   as each module page's "Customize" section lists.
3. **An API type exposes only API types.** Its public and protected constructors, methods and fields,
   its superclass and its interfaces use only API types, JDK types and library types. So when a
   Customize section names a type, every Stratum type a service needs to build its replacement is API
   too.
4. **No class moves.** Packages stay as they are, so the label adds no cycle and changes no
   behaviour.
5. **No package cycles, anywhere.** Two packages never depend on each other, directly or through
   other packages, inside a module or across modules. A cycle is fixed by moving the type both
   packages need, never by loosening the check.
6. **Internal is never a way around a rule.** Stratum's modules may use an internal type of
   `stratum-core` or of the library they are built on, such as `stratum-web`. A starter still never
   uses another starter's types (ST-ENG-11).
7. **The 0.1.0 API check** (japicmp or Revapi, planned) ignores types that carry the label. Its
   retention is `CLASS`: the label is in the bytecode those tools read, and is not loaded at run time.

## Consequences

- A service sees in its IDE, and in the Javadoc, which Stratum types it may rely on.
- The compiler does not stop a service from using an internal type. The label and the docs warn it.
- A new public type with no label is API by default. Reviewers and the judge check that each new
  public type is either meant for services or labelled (ST-ENG-56).
- Turning an API type into an internal one, or changing its public members, is a breaking change and
  is listed in the release notes (ST-DOC-10).
