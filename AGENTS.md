# AGENTS.md: rules for every agent working on Stratum

This file is the **single source of agent instructions** for this repository. Claude Code, Codex, Copilot, Cursor,
Gemini and any other assistant follow it. `CLAUDE.md` only imports this file; never put rules there. When a rule
changes, change it here (or in the `docs/agents/` file that owns it) and nowhere else.

An explicit instruction from the owner in the current conversation overrides this file. A nearer `AGENTS.md` in a
subdirectory overrides this one for that subtree.

## The project

- **Stratum** is an opinionated SDK on top of the latest stable **Spring Boot (4.1.x)** and **Java 25**, built with
  **Maven** as a multi-module project. Read `README.md` for the purpose, the planned modules and the layout.
- It gives microservices standard, auto-configured cross-cutting components: logging, observability, tracing,
  security, REST and OAuth2 clients, resilience, data, caching, messaging, cloud connectors and Testcontainers-based
  test support. Business teams add a starter, set a few `stratum.*` properties and write only business code.
- Stratum is used by many services at high volume and high throughput. A bug, a slow path or a leaked secret here is
  copied into every service that depends on it, so the bar is production quality from the first commit.
- Stratum holds **no business logic**. Domain code belongs in the services that use it.

## How to work (always)

- **Be honest.** Never present a guess as a fact. Say what you verified and how, and what you did not verify.
- **Never claim work you did not finish.** "Done" means every claim is backed by evidence you produced yourself
  (a command and its output, a test, a `file:line`). If something is partial, say exactly which part. Every
  non-trivial change is checked by an independent judge (see "Judge review" below), so unproven claims are found.
- **Get past blockers.** When something stops you, find another legitimate way (another source, a test, a
  workaround) or ask one clear question. If it truly cannot be done, say so and why. Never skip it silently.
- **No patches.** Find the root cause, prove it with a test, then fix the design. When a component keeps needing
  fixes, question the design.
- **No blind acceptance or rejection of reviews.** Check every review comment (from the owner, another agent or a
  judge) against the code and, where possible, by running it. Accept it when the check shows it is right; push back
  with evidence when it is not. Partial agreement is fine: say which part and why.
- **Keep it simple, precise and concise.** Plain words, real numbers, short answers.
- **One builder per change.** Before starting, check open pull requests and branches for work on the same module or
  rule. Never build a second version of something already in progress.
- **Hard lines:** secrets, tokens, passwords and personal data never go into code, config, logs, tests, commits,
  pull requests or chat. Never disable, skip or loosen a test or a quality gate to get a green build.

## File size limits (mandatory)

Small files are easy to review, so every file has a hard line limit:

| File kind | Limit | Examples |
|---|---|---|
| Code and configuration | **400 lines** | `.java`, `pom.xml`, `.yml`, `.xml`, `.json`, `.sh`, `.properties` |
| Text-only files | **500 lines** | `.md`, `.txt`, `.adoc` (READMEs, guides, ADRs, rule files) |

- Anything that would pass its limit is **split into smaller single-purpose files**, never squeezed: no joined
  statements, overlong lines or removed blank lines to dodge the limit. A class near 400 lines is a sign it has more
  than one responsibility; extract a collaborator, strategy or mapper.
- Split a long document by topic into several files and link them from an index (as `docs/agents/` does here).
- A change may edit a file that is already over its limit only if the change makes it smaller.
- Check before you push: `sh scripts/check-file-sizes.sh` (it lists every tracked file over its limit and exits 1).
  Excluded: `LICENSE` and generated files.

## Engineering rules (summary)

The full rules live in the files below. Read the ones that match your change before writing code.

| Topic | Owning file |
|---|---|
| Design principles, Java and Spring Boot conventions, auto-configuration, performance | [docs/agents/engineering.md](docs/agents/engineering.md) |
| Unit, auto-configuration and integration tests | [docs/agents/testing.md](docs/agents/testing.md) |
| Security, secrets, personal data, logging | [docs/agents/security.md](docs/agents/security.md) |
| Independent judge review | [docs/agents/judge.md](docs/agents/judge.md) |
| Keeping the documentation current | [docs/agents/documentation.md](docs/agents/documentation.md) |

The essentials, every time:

- **SOLID, KISS, DRY and clean code:** clear names, small single-purpose classes and methods, no duplication, no
  dead code, no premature abstraction. Comments explain *why*, never *what*.
- **Modular and loosely coupled:** each starter is opt-in and owns one concern. Depend on interfaces; every external
  system sits behind one. Reuse an existing Stratum module before writing a second version of the same behaviour.
- **Every bean is overridable:** `@ConditionalOnMissingBean` on every bean Stratum defines, every default is a
  `stratum.<module>.*` property bound through `@ConfigurationProperties`.
- **Built for high volume:** timeouts on every remote call, bounded pools, queues and caches, no blocking on hot
  paths, retries only for idempotent operations on transient failures.
- **Use Java 25 and Spring Boot 4 as they are today.** Records, sealed types, pattern matching, virtual threads,
  JSpecify null-safety. Do not copy Spring Boot 2 or 3 examples without checking the Boot 4 migration notes.

## Build and checks

- Requires JDK 25 and Maven 3.9+. Once the build exists, `mvn verify` must pass before every push. It runs
  formatting, static analysis, unit tests (`*Test.java`, Surefire) and integration tests (`*IT.java`, Failsafe).
- For a change to one module, build that module and the modules that depend on it (`mvn -pl <module> -am verify`),
  then the full `mvn verify` before the pull request is marked ready.
- A change of text files only (`.md`, `.txt`, `.adoc`) needs no build: run the file-size check and read the
  rendered result.
- Fix the cause of a failing gate. Never add a suppression, `@Disabled`, `-DskipTests` or a looser rule to pass.

## Git and pull requests

- Never push to `main`. Work on a branch and open a pull request.
- One pull request does one thing. Keep it small enough to review in one sitting.
- The pull request description has a **Claims** section: each acceptance criterion with its evidence (command and
  output, test name, `file:line`). The judge checks these claims; see [docs/agents/judge.md](docs/agents/judge.md).
- A pull request that changes behaviour, a default, a property or a public API also updates the owning document in
  the same pull request (see [docs/agents/documentation.md](docs/agents/documentation.md)).
- Commit messages say what changed and why, in the imperative ("Add trace propagation to the HTTP client starter").

## How to answer the owner

1. Lead with the result or the decision needed, in one or two sentences.
2. Then the evidence: what you ran and what it showed.
3. Then the risks, open questions and what is not done.
