# Keeping the documentation current

Stratum is only useful if a developer can find out what it does, how to configure it and how to use it without
reading its source. Documentation is part of every change and is reviewed like code. Index:
[AGENTS.md](../../AGENTS.md).

## What is documented where

| Document | Owns |
|---|---|
| `README.md` | What Stratum is, why it exists, the module list, a quick start, how to build. A summary that links out. |
| `<module>/README.md` (each starter, `stratum-test`, `stratum-parent`, `stratum-bom`) | What the module sets up, every `stratum.<module>.*` property with its type, default and meaning, how to override each bean, a short usage example. |
| `docs/` guides | Topics that span modules: getting started, upgrading, testing a service, architecture. |
| `docs/adr/NNNN-<title>.md` | Architecture decisions: context, decision, consequences, alternatives considered. Never edited after acceptance; a new ADR supersedes an old one. |
| `CHANGELOG.md` or release notes | What changed in each release, breaking changes and migration steps. |
| `AGENTS.md` and `docs/agents/` | Rules for agents. |

## Rules

- **ST-DOC-01 Same pull request.** A change to behaviour, a default, a property, a bean, a public API or a dependency
  version updates the owning document in the same pull request. A pull request that leaves a document describing the
  old behaviour is not done, and the judge fails it.
- **ST-DOC-02 One functionality, one owning document.** Describe each thing in exactly one place. Everywhere else,
  give one line and a link. Never a second description that can drift.
- **ST-DOC-03 Update in place.** Change the owning document. Never add a dated summary, handover note or "update"
  file as the place where current behaviour is described.
- **ST-DOC-04 Every property is documented.** Each `stratum.*` property appears in its module README with type,
  default and meaning. The Javadoc on the `@ConfigurationProperties` field says the same, since it feeds IDE
  completion.
- **ST-DOC-05 Examples work.** Code and YAML examples compile and run against the current version. Prefer examples
  taken from a sample or a test so they cannot rot silently.
- **ST-DOC-06 Mark what is planned.** Anything not built yet is clearly labelled as planned. Never describe planned
  behaviour as if it exists.
- **ST-DOC-07 Size.** No text-only file over 500 lines. Split a long document by topic into several files with an
  index that links them.
- **ST-DOC-08 Plain language.** Short sentences, plain words, real names and numbers. Explain any term a Spring Boot
  developer may not know.
- **ST-DOC-09 Breaking changes.** A breaking change gets a migration note in the release notes: what changed, who is
  affected and the exact steps.

## Reviewing documentation

The judge reads the changed documents against the code: every property, default and example must match what the
code does at the pull request's head commit. Planted faults include "change a default in code; does a test or a
document check catch it?"
