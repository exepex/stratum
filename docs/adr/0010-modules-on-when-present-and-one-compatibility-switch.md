# ADR 0010: Modules on when present, and one compatibility switch

**Status:** Accepted, 2026-11-03, by the owner. Not built yet: items 2a and 2b build it (see
[Follow-up items](#follow-up-items)). It replaces these parts of earlier records:

- [ADR 0003](0003-bundle-starters-and-module-switches.md) decisions 2 and 4. Decisions 1 and 3 stand.
- [ADR 0006](0006-separate-mvc-and-webflux-starters.md) decision 3, only its rule that the `web`
  module is off until `stratum.web.enabled=true`. Both web starters stay one module, `web`, and read
  the same `stratum.web.*` settings, except the compatibility switch, which moves to `stratum-core`.
- [ADR 0008](0008-error-format-and-backward-compatible-mode.md) decisions 2, 7 and 8, and the last
  sentence of decision 4. Decisions 1, 3, 5 and 6, and the rest of decision 4, stand, read with the
  new key.

It also brings back the `stratum.<module>.enabled=false` step of
[ADR 0001](0001-coexistence-with-company-frameworks.md) decision 2, which ADR 0003 replaced. Every
other part of ADR 0001 stands.

[← Documentation home](../README.md)

## Context

- **Modules are off until a service turns them on (ADR 0003).** A team that adds a starter gets
  nothing until it writes `stratum.<module>.enabled=true` for each module. Most of the documentation
  repeats that line: `git grep -l 'enabled=true' | wc -l` gives 91 files at `87dc6da`. A missed
  line is easy to overlook: the only sign is one startup line, from `ModulesLeftOffNotice`.
- **The compatibility switch belongs to the web module only (ADR 0008).** The planned data and Kafka
  starters also have contracts with consumers, such as query timeouts and dead-letter headers. A
  switch per module would add keys that every service has to set, and ST-ENG-34 keeps the number
  of keys low.
- **ADR 0008 chose `true` as the default** to protect the consumers of a service that is already
  running. That reason no longer decides the default: no service runs on Stratum yet, and no module
  is released (`README.md`). A running service keeps its contract with its consumers (ST-ENG-28) by
  setting `stratum.backward-compatible=true` in its first release on Stratum.

## Decision

Neither decision is built yet. Until item 2a, modules stay off by default:
`StratumModuleSwitch.java:25` reads `.orElse(false)`. Until item 2b, the key is
`stratum.web.backward-compatible`, with the default `true`. Every page keeps describing today's
code until then.

### 1. Modules are on when present

1. Every module is on when its starter is on the classpath. A service turns one off with
   `stratum.<module>.enabled=false`. The switch is still `@ConditionalOnStratumModule`, and it is
   still read from properties only.
2. A missing required value stops startup with a message naming the key and
   `stratum.<module>.enabled=false`. A module never switches itself off quietly.
3. No single starter depends on another starter at compile or run time. A bundle starter lists
   single starters, as ADR 0003 decision 1 says. A single starter may use another starter in test
   scope only, as `stratum-starter-webmvc` and `stratum-starter-webflux` use
   `stratum-starter-observability` today.
4. Starters share code and settings through a library in `libs/`. Shared code moves there only when
   a second starter needs it. At run time, starters meet through Spring, and a starter never changes
   its behaviour based on another starter's settings (ST-ENG-11).

### 2. One global compatibility switch

1. `stratum.backward-compatible` lives in `stratum-core`, defaults to `false`, and replaces
   `stratum.web.backward-compatible`. The web key is replaced before any release, so ADR 0008's
   "never removed" no longer holds.
2. The mode protects external contracts only: bodies, statuses, headers, message formats and the
   SLA, as consumers see them. Everything internal always follows Stratum.
3. Each setting is either external or internal:

   | Setting | Class | When it applies | Today |
   |---|---|---|---|
   | Query timeout (30s) and transaction timeout (60s) | external | strict mode only (`stratum.backward-compatible=false`) | planned |
   | `open-in-view=false` | external | strict mode only | planned |
   | `hibernate.query.fail_on_pagination_over_collection_fetch=true` | external | strict mode only | planned |
   | Web error body and web timeouts; DB error mapping | external | strict mode only | web: built, under `stratum.web.backward-compatible`; DB: planned |
   | Kafka dead-letter headers | external | strict mode only | planned |
   | Clean SQL exceptions (JDBC boundary) | internal | always | planned |
   | Logs, spans, redaction, metrics | internal | always | built |
   | 3s pool wait (`connection-timeout`) | internal | always (kept knowingly: a fast, visible failure protects the service) | planned |
   | `ddl-auto=validate` | internal | always | planned |
   | Leak alarm | internal | always, once its benchmark passes | planned |
   | `hibernate.jdbc.log.errors=false`, `hibernate.jdbc.log.warnings=false` | internal | always | planned |

4. The mode makes no other promise. ADR 0008 decision 7 listed what a new feature may and may not
   do in the mode; the owner dropped that list on 2026-11-03. It is one switch, with a wider scope
   than ADR 0008's: it does not add a switch inside the mode.

## Consequences

- A new service adds a starter and gets that module on, in strict mode, without writing a line.
- A running service that adopts Stratum sets `stratum.backward-compatible=true`, and then follows
  the migration path of ADR 0008 decision 4 to `false`.
- The internal settings apply in both modes. A running service gets them, such as the 3s pool wait
  and `ddl-auto=validate`, on the day it adopts the starter. [Migrating a service](../guides/migrating-a-service.md) says
  so once those settings are built.
- Next to a company framework, a team turns off an overlapping module with
  `stratum.<module>.enabled=false`, as ADR 0001 decision 2 says.
- Adding a starter to a bundle now turns on a module in every service that uses the bundle. It goes
  in the release notes as a breaking change (ST-DOC-10).
- How the decision weighs (ST-AG-14): the default protects services, because strict mode keeps
  every timeout and limit (ST-ENG-25, ST-ENG-52) and redaction always applies. Startup stops on a
  missing value instead of running half-configured. No request path changes; startup log volume
  changes only through `ModulesLeftOffNotice` (item 2a).
- ADRs 0001, 0003, 0006 and 0008 are not edited: an accepted record never is (documentation rules).

### Follow-up items

Each is its own item, with its own track and judge. Counts were taken at `87dc6da`.

- **Item 2a, modules on when present:**
  - `StratumModuleSwitch.java:25` and `EarlyModuleSwitches.java:82` change `.orElse(false)` to
    `true`, and `ModulesLeftOffNotice` follows;
  - rules ST-ENG-10, ST-ENG-21, ST-ENG-25, ST-ENG-34, ST-ENG-52 and ST-TEST-20;
  - `docs/guides/company-framework.md` and `docs/modules/bundles.md`;
  - the files that mention `enabled=true` (`git grep -l 'enabled=true' | wc -l` gives 91) and the
    files that say "off until" (`git grep -l 'off until' | wc -l` gives 16);
  - "replaced in part by ADR 0010" on the ADR 0003 row of `docs/README.md`.
- **Item 2b, one compatibility switch:**
  - `stratum.backward-compatible` in `stratum-core`, replacing `stratum.web.backward-compatible` in
    the files that mention it (`git grep -l 'stratum.web.backward-compatible' | wc -l` gives 23);
  - ST-ENG-25 and ST-ENG-52 name the new key, and ST-ENG-25 loses its sentence on ADR 0008
    decision 7 (`docs/agents/engineering.md:153-155`);
  - the rule "Backward-compatible mode keeps external contracts unchanged; everything internal
    always follows Stratum's defaults", added to `AGENTS.md` (Engineering rules) and to ST-ENG-25,
    as the owner decided on 2026-11-03;
  - "replaced in part by ADR 0010" on the ADR 0008 row of `docs/README.md`.
