# Documentation rules

Part of the agent rules in [AGENTS.md](../../AGENTS.md); every agent follows them. Each rule has an ID so that reviews
and judge verdicts can cite it.

Stratum is useful only if a developer can learn what it does, how to configure it and how to use it without reading
its source. Readers range from junior developers using Spring Boot for the first time to experienced engineers, and
the documentation must work for both. Documentation is part of every change and is judged like code.

## Where things are documented

| Document | What it covers |
|---|---|
| `README.md` | What Stratum is and why it exists, its modules, a quick start and how to build. A clear overview that links to `docs/` for detail. |
| `docs/README.md` | The index of the user documentation. Every page in `docs/` is linked from it, except the agent rules in `docs/agents/`, which `AGENTS.md` indexes. |
| `docs/getting-started/` | Installing Stratum, the core concepts and a first service, step by step. |
| `docs/configuration/` | How configuration works, overriding defaults, profiles, and `reference.md`: every `stratum.*` property in one table. |
| `docs/modules/<module>.md` | One page per module: what it sets up, each of its properties (type, default, meaning), how to replace each bean, and usage examples. |
| `docs/contributing.md` | How a person proposes a change: issues, branches, build commands. It links to `AGENTS.md` for the rules and repeats none of them. |
| `docs/guides/` | Topics that span modules: architecture, migrating a service, upgrading, troubleshooting, FAQ. |
| Release notes | What changed in each release, breaking changes and the steps to migrate. |
| `AGENTS.md` and `docs/agents/` | The rules for agents. `AGENTS.md` links every file in `docs/agents/`. |

## Rules

- **ST-DOC-01 Same pull request.** A change to behaviour, a default, a property, a bean, a public API or a dependency
  version updates every document that describes it in the same pull request. A pull request that leaves a document
  describing the old behaviour is not finished, and the judge fails it.
- **ST-DOC-02 One place per topic.** Each topic is described fully in one document. Other documents link to it with
  one line instead of describing it again. The one exception is `docs/configuration/reference.md`, which lists every
  property with a link to its module page; the two must agree.
- **ST-DOC-03 Edit in place.** Update the document that owns the topic. Do not add dated summaries, handover notes or
  "update" files.
- **ST-DOC-04 Every property documented.** Each `stratum.*` property appears on its module page and in the reference
  table, with type, default and meaning. The Javadoc on its `@ConfigurationProperties` field says the same, because
  IDEs show it.
- **ST-DOC-05 Both configuration formats.** Every configuration example is shown in both `application.properties` and
  `application.yml`, side by side or in two consecutive blocks, with the same values.
- **ST-DOC-06 Examples that work.** Code and configuration examples compile and run against the current version.
  Where possible, take them from a sample or a test so they cannot drift from the code.
- **ST-DOC-07 Written for every reader.** Short sentences and plain words. Explain each term a developer new to Spring
  Boot may not know, the first time it appears on a page. Show complete steps, not fragments that assume knowledge.
- **ST-DOC-08 Planned is labelled.** Anything not built yet is marked as planned. Never describe planned behaviour as
  if it exists.
- **ST-DOC-09 Size.** No document over 500 lines. Split a long document by topic and link the parts from
  `docs/README.md` or a section index.
- **ST-DOC-10 Breaking changes.** Every breaking change has a migration note in the release notes: what changed, who
  is affected and the exact steps.

## How documentation is checked

The judge and the documentation role read each changed document against the code at the pull request's head commit.
Every property name, default, bean name and example must match what the code does. Each of these is a finding: a
link that leads nowhere, a user documentation page missing from `docs/README.md`, an agent rule file missing from
`AGENTS.md`, and a configuration example shown in only one format.
