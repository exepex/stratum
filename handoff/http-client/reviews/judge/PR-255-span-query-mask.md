# Judge verdict: PR #255, span query mask (#240), branch `claude/project-thread-64l8fc`, head `03bb314`

Track: Feature. Review step: code, round 1 (the note PASS is `reviews/judge/OBS-span-query-mask-note.md`).
Judge model: Claude Opus. I read `AGENTS.md`, all six topic files, the design note with its addendum, and the note verdict.

**FAIL**, on one minor finding: the "encoded again" step has no test that fails when it is removed. I found no leak.

## Gate
The PR is open and ready for review, not a draft. Base `e410a17` is the merge base, and GitHub reports `mergeable_state: clean`
(main has since moved to `baad5cf` with #253, but no files overlap). At `03bb314` all 7 check runs are green or skipped:
Verify and SBOM, CodeQL (actions and java-kotlin), SonarCloud, and the wiki check. There are 3 Codex P1 threads (`,`, `?`,
percent-encoding). Each is accepted, fixed and resolved, and I reproduced each fix (probes below). The Claims are
complete and the design note is present.

## Gap list
| Requirement | Result | Evidence |
|---|---|---|
| Span keeps scheme, host and path | MET | `TelemetryUrlFilter.java:194-197` masks only from the first `?`/`#`. Probe: `/a&b/c?…` path kept. `keepsHostAndPath` |
| Query values masked like logs, built-in rules only | MET | `QueryValueMasker.java:28-32`, the same name and content chain as `Redactor.from:84-90` minus the service's own rules. 40 probe inputs, below |
| Rules reused, not copied | MET | 7 classes renamed at 94-98% similarity. `Redactor.java` imports them. The logging tests pass unchanged |
| MVC and WebFlux the same | MET | One `ObservationFilter`. The IT asserts `MASKED_QUERY` on both the RestClient and the WebClient span (`ObservabilityStarterIntegrationTest:122,131`) |
| No new key | MET | No properties, metadata or `spring.factories` file in the diff. Reads Boot's `max-attribute-value-length` (`ObservabilityAutoConfiguration:86-89`) |
| Bound to the span limit, no partial value | MET | `keptQuery:205-216`. Probes at limit 40: a param ending at the limit is kept, one past it is dropped, a path `&` drops the query, `#` is honoured |
| Docs updated | MET | traces page, both READMEs, known-gaps row #254, architecture guide. Text checked against the code |
| Extra keys/patterns on spans | Out of scope | Owner chose A (15:19Z). Issue #254 |
| Line counts | MET | All 40 files match the Claims. Largest: observability README 495/500, traces 467/500 |

## Findings
1. **Minor (ST-TEST-01).** The Claims say "A changed value is encoded again", but no test breaks when that step is removed.
   I planted `: maskedText;` in place of `UriUtils.encodeQueryParam(...)` (`QueryValueMasker.java:71`). All 33
   `TelemetryUrlFilterTest` cases still pass, yet the span URL changes:
   `?q=Bearer%20abcdefghijkl` gives `?q=Bearer ****`, and `?q=a%26b%204111111111111111` gives `?q=a&b ****1111`, which
   creates a parameter `b` that was never sent. Expected fix: add one case to `URLS_AND_MASKED_URLS`, such as
   `https://partner.com/find?q=Bearer%20abcdefghijkl` → `https://partner.com/find?q=Bearer%20****`, which is what the head
   gives today.

## What I ran
- JDK 25 and Maven 3.10.0 (wrapper zip, sha256 matches). `mvn -pl libs/stratum-redaction,starters/stratum-starter-logging,starters/stratum-starter-observability -am install`
  → BUILD SUCCESS, all static checks and coverage gates met, and `ObservabilityStarterIntegrationTest` ran 6 tests with 0 skipped.
- Probes against `TelemetryUrlFilter` at the head; all were masked:
  - Casing and name forms: `PASSWORD`, `X-API-KEY`, `apiKey`, `user[password]`, `user%5Bpassword%5D`,
    `credentials.password`, `password[]`.
  - Values hidden inside another value: `a=1?password=…` and `a=1;password=…` (the inner pair is masked).
  - Encoded and broken values: `token=a%26b`, `password=%FF%FE`, `secret=a%2`.
  - Shapes: JWT, `redirect=https%3A%2F%2Fu%3Apw%40x.com`, IBAN, `Bearer%20…`, SSN, phone, `pin`, and a fragment `#…?password=`.
  - Spring's `encode().buildAndExpand` gives `to=jane.doe%40example.com` → `to=****.com`.
- Costs: 1 MB `eyJ…`, digit and pair-chain queries cost 8, 4 and 60 ms. A masked URL can grow past the limit: 1,038 chars of
  `password=a&` became 1,289. OpenTelemetry then cuts inside a value that is already masked, so nothing leaks.

## Planted faults
I planted 9 faults, one at a time on a copy compiled ahead of `target/classes`, so the sources were never changed.
6 were caught:
- No bound (`if (true)`): 4 `LongUrls` cases fail. The speed test also fails 2 cases (pairs 290 ms, `@` 62 ms).
- Mask after cutting at the limit: 3 cases fail.
- `#` dropped from the separators: the fragment case fails.
- No name rule: 7 cases fail.
- No content rule on named parameters: 3 cases fail.
- Masking made 10× slower: no failure, because the cost is Spring's URI parse and masking is capped at 1,024 chars.
  This is expected.
3 survived:
- Re-encoding removed: Finding 1.
- Bound off by one (`maxUrlLength + 1`): harmless, because the whole parameter is masked before OpenTelemetry cuts it.
- The "unchanged" check compares the raw text: harmless, because re-encoding gives the same bytes.

## Rules checked
| Rule | Result |
|---|---|
| ST-ENG-42/47 | `@RequiredArgsConstructor` makes the new public constructor. No new static methods outside `@UtilityClass` |
| ST-ENG-14/15 | Javadoc on every new type and method, and the field Javadoc for the Lombok constructor. Brief |
| ST-ENG-41 | `@NullMarked` package-info in `stratum-redaction` |
| ST-ENG-46 | Single-expression lambdas |
| ST-ENG-56/57 | `QueryValueMasker` is package-private. The 3 newly public types carry `@StratumInternal` and `RedactionArchTest` checks them. Arch tests pass with no cycle |
| ST-TEST-15/17 | `@FieldSource` constants, plus `@Nested` `QueryMasking` and `LongUrls` |
| ST-SEC-01..06 | No secret in code or tests (`LEAK-…` markers only). Untrusted URL traced from Spring's `http.url` to the exporter |

## Notes
- A query built with `URLEncoder` writes spaces as `+`. So `q=4111+1111+1111+1111` and `q=DE89+3704+…` stay unmasked on the
  span. Logs miss the same text (`builtInRules` on `…?q=4111+1111+1111+1111` leaves it as it is), so the requirement
  "masked like logs" holds. Spring's builders write `%20`, and that form is masked. It could become an issue if the owner wants it.
- `commons-validator` now reaches services that use only the observability starter, through `stratum-redaction`. Any
  service that also uses logging already had it.
