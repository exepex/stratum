# Judge verdict: design note of H1 (blocking HTTP client starter on ADR 0011), Feature track, review steps 1, 2, 3 and 5, round 1

Note: `design/http-client-libraries/h1-design-note.md` (117 lines). Sources: ADR 0011 at `origin/main` b509a9f
(`docs/adr/0011-http-client-starters.md`), `gap-review-merged.md` "Decisions", `spike-result.md`, PR #79 (head
0978a0a, open, base 1eb0460) and its verdict `PR-79-http-client-base.md`, the repo at b509a9f. Fresh judge, no part in
building the note, no owner decision reopened. Model: Claude Fable 5.1.

**FAIL.** Two should-fix findings and two minor ones, all text fixes to the note. The three scope calls hold.

I read AGENTS.md and all six topic files. Text-only item: step 4 (build) skipped. Nothing changed in the repo; nothing
posted on GitHub.

## Gap list

| Requirement | Result | Evidence |
|---|---|---|
| Gate: note complete, questions 1 to 4 answered, each at most 3 lines | MET | note:23-41; answers are 3, 3, 3 and 3 lines |
| Scope call 1: blocking only; reactive bundle drops the blocking starter | MET | ADR 1.1 and "Follow-up items" list the reactive starter as its own item. On main the reactive bundle lists `stratum-starter-http-client` (`stratum-starter-reactive-microservice/pom.xml:30`), a stub with only a `pom.xml`, so no service loses behaviour. `bundles.md:27` is in the note's document list (note:107) |
| Scope call 2: Stratum no longer sets Boot's global `spring.http.clients.*` timeouts | MET | ADR "PR #79 (H1) must change: its keys move into the Stratum block"; ADR 2.1 and 9 ("never wraps, replaces or checks a foreign client"). Main sets no such key (`grep -rn spring.http libs/stratum-core/src/main` = 0). The 2026-10-31 decision is superseded by the ADR the owner accepted |
| Scope call 3: fresh branch from main, #79 superseded and closed by the owner | MET | PR #79 is open, `mergeable_state: dirty`, built on the old key model (its Claims R2, R3); the owner's H1 go of 01:50Z is quoted in note:3. See Notes on the commit count |
| H1 scope = ADR follow-up item "base, settings block, pool, retry off, README" | MET | note:8-17 against ADR 1.2, 1.4, 2.1, 2.2, 2.4, 4.1, 5.1 to 5.5 |
| Keys and defaults match ADR 2.4 | MET | note:50-59: url, 2s, 5s, 50, 2s, 30s, 5m, false; the 11 later keys named as later (note:19-21) |
| Startup stop on a Boot `serviceclient` key, reads configuration only (ADR 2.2) | MET | note:67-68, 72-73 |
| `Mono`/`Flux` without the reactive starter stops startup (ADR 1.1) | MET | note:69 |
| Apache retry off, stale check, Keep-Alive, lifetime, redirects off, no network at startup (A1, A4, A5, A13, ADR 5) | MET | note:10-11, 76-84 |
| Q1: API and `@StratumInternal` list (ST-ENG-56) | PARTLY | F1 |
| Q2: Spring reuse, alternative, module and package, no cycle, keys with cases, Java customizer | MET | note:29-31, 45-54; the spike facts match `spike-result.md` items 1, 2, 9 and finding 2 |
| Q3: eight parameters, hostile inputs, thread hops | PARTLY | note:76-84; M2 |
| Q4: each owner decision with the test that protects it | NOT MET | F2 |
| Documents to update (ST-DOC-01, -05, -08) | MET | note:103-108; `basics.md:250` and `reference.md:140` already describe the ADR model |
| Sizes (ST-AG-10), secrets (ST-SEC-01) | MET | 117 lines; no values |

## Findings

**F1. Should fix (ST-ENG-56, design-note question 1).** note:26 lists the API as "`@StratumClient`, the two
customizers" and names nothing as internal. A service that implements `HttpClientPropertiesCustomizer` receives and
returns `HttpClientProperties` and builds `ClientSettings`, so those records and their Lombok builders are API too:
"each Stratum type needed to build a replacement is API" (engineering.md:300-301). The web module does exactly this
(`WebArchTest.java:33-40` lists `WebProperties`, its builder and each nested settings type). **Fix:** one line in
question 1 or "Modules and packages": API = `@StratumClient`, `HttpClientPropertiesCustomizer`, `HttpClientProperties`,
`ClientSettings` and their builders, `StratumClientCustomizer`; every other public type (registrar, group configurer,
pool builder, startup checks, auto-configuration) carries `@StratumInternal`.

**F2. Should fix (design-note question 4, ST-TEST-01).** note:40-41 says "Each is matched to a test in the details
below", but the details name a test only for the timeout, the pool, Apache's retry (note:98-99), pool close (note:80)
and two interfaces in one group (note:117). No test is named for: the `SX-HTTPC-0005` Boot-key stop (ADR 2.2, owner
00:45Z), the `SX-HTTPC-0006` `Mono`/`Flux` stop (ADR 1.1), `max-wait` (A2), `idle-timeout` and the 2s stale check
(A5), `connection-lifetime` (A4), Keep-Alive (A5), redirects off (ADR 5.4), no network at startup (A13),
`shared-pool` (ADR 5.1), and the Java customizer winning over YAML (ADR 1.4). **Fix:** a short table, decision → test
name, one line each; the spike's `HttpClient5Test` shows how lifetime, stale check, Keep-Alive and pool wait are
tested with short durations.

**Minor**
- M1. note:67-69 numbers the two startup stops `SX-HTTPC-0005` and `-0006`, next to the ADR's runtime codes 0001 to
  0004. Every other module numbers startup stops from 1001 (`error-codes.md`: SX-CORE, -LOGGING, -OBSERVABILITY,
  -DATA, -CACHE and -WEB, 32 codes, all 1xxx). No written rule fixes the range, so this is consistency only.
  Fix: `SX-HTTPC-1001` and `-1002`, or one line saying why 0005/0006.
- M2. Question 3 asks for each thread hop (ST-ENG-53). note:34-36 says nothing. "None" needs its fact: one clause
  such as "no thread hop: a blocking call runs on the caller's thread".

## Planted faults

Not applicable to a text-only note. Checked by search and reading instead: the reactive bundle POM; the error-code
ranges of all modules; the web module's API list; the ADR's key list and sections 1, 2, 4.1, 5 and 9 against the note.

## Notes (not findings)

- note:5 says PR #79 is "478 commits behind main". `git rev-list --count 0978a0a..origin/main` gives 335 (68
  first-parent). Harmless, but ST-AG-01: give the command or drop the number.
- ADR 5.4 "Gzip is accepted" is not in the note. Apache HC5 enables content compression by default, so H1 needs no
  code for it; not verified by me on 5.6.5. One line in the README would settle it.
- A plain Boot `RestClient.Builder` in a Stratum service gets no timeout after scope call 2, as in plain Boot. ADR 9
  covers it; the rewritten module page should carry ADR 9's one sentence (marked planned) so a team does not assume
  Stratum guards every client.

## Not verified

Library behaviour was taken from `spike-result.md`, not re-run. Owner decisions were checked against
`gap-review-merged.md` and the ADR, not the chat. Not merged; nothing posted on GitHub.
