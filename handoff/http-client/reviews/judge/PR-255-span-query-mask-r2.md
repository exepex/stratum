# Judge verdict: PR #255, span query mask (#240), branch `claude/project-thread-64l8fc`, head `47806cd`

Track: Feature. Review step: code, round 2. Round 1 (head `03bb314`, Claude Opus) is `PR-255-span-query-mask.md`.
Judge model: Claude Fable. I read `AGENTS.md`, all six topic files, the round 1 verdict and the Claims at the head.

**PASS.** The round 1 finding is fixed and tested, the two `+`-as-space fixes work, and nothing else in the PR changed. Not merged.

## Gate
Open, ready for review, not a draft. Base `da23adc` is the merge base; `mergeable_state: clean`. At `47806cd` all 5 check runs are
green: Verify and SBOM (run 42671063588), CodeQL (actions and java-kotlin), SonarCloud. Codex has 5 P1 threads, all accepted,
fixed and resolved. The two new ones (`+` as a space, 17:32Z; `+` pass skipped once the first pass masked, 17:42Z) I reproduced
with the planted faults below. The Claims are updated for the head (faults 7 to 10, Codex 4 and 5, rebase, CI).

## What changed since round 1
The round 1 head is no longer on GitHub (rebased). The PR's 7 commits against `main` differ from round 1 in two files only:
- `QueryValueMasker.java` +14: `withSpacesMasked` runs the rules a second time on the already masked text with each `+` read as
  a space, and keeps the `+` form when that finds nothing more.
- `TelemetryUrlFilterTest.java` +18: 2 re-encode cases (round 1 finding) and 4 `+` cases (two cards, an IBAN with `note=a+b`,
  `jane+news@example.com`).
The rebase onto `da23adc` (#256) merged the root POM and the BOM; `git diff origin/main 47806cd` is still 40 files.

## Gap list
| Requirement | Result | Evidence |
|---|---|---|
| Round 1 finding: re-encode step tested | MET | Planted `: maskedText;` for `encodeQueryParam`: cases 12 and 13 fail (`q=Bearer%20abcdefghijkl`, `q=a%26b%204111111111111111`) |
| `+` written by `URLEncoder` is masked | MET | Probes: `q=4111+1111+1111+1111` → `****1111`; `q=Bearer+abcdefghijkl` → `Bearer%20****`; `?4111+1111+1111+1111` (no name) → `****1111`; `#frag=4111+…` → `****1111` |
| `+` masked also when the first pass already masked | MET | `ref=4111+1111+1111+1111*eyJaaaaaa.aaaaaa` → `****1111*****`; reversed order `eyJ…*4111+…` → `*********1111` |
| Ordinary `+` kept as sent | MET | `note=a+b`, `x=1+2`, `q=1+2+3+4`, `q=a%20b+c`, `q=+++++…` all unchanged |
| Span keeps scheme, host, path; bound; no new key; docs; MVC = WebFlux | MET (unchanged since round 1) | Same code as round 1 except the 14 lines above; `keepsHostAndPath`, `LongUrls`, `urlFilterFollowsSpanLimit` pass; docs text (`observability-traces.md:114-140`, README row 111) matches the code |
| Line counts | MET | All 40 files match the Claims; `QueryValueMasker` 100, `TelemetryUrlFilterTest` 254. Largest: observability README 495/500, traces 467/500. Every file ends with a newline |

## What I ran
- JDK 25 (apt), `./mvnw -B -pl libs/stratum-redaction,starters/stratum-starter-logging,starters/stratum-starter-observability -am install`
  at `753d36f` and again at `47806cd`: both EXIT 0. At the head: redaction 124 unit tests, logging 538 unit + 35 IT,
  observability 264 unit + 60 IT, 0 failures (counted from the `TEST-*.xml` reports).
- `TelemetryUrlFilterTest` through a JUnit launcher against the head classes: 39 tests, 0 failures.
- 41 hostile probes through `TelemetryUrlFilter` (20 at `753d36f`, 21 at the head), including `%2B`, `%ZZ+4111…` (broken escape
  then a card: masked, re-encoded as `%25ZZ%20****1111`), `token=a=b=c`, `password=100%25`, `next=https%3A%2F%2Fu%3Apw%40x.com…`
  (masked `https://****@x.com/a?b%3D1`), two cards in one value, a JWT after `%E2%82%AC`, an emoji before an IBAN.
- Cost: a 1,024-character `q=4111+4111+…` value, which runs both passes, costs 96 µs per call.

## Planted faults (on a copy compiled ahead of the classpath; sources untouched, `git status` clean)
6 planted, 6 caught:
1. No `+` pass: 2 fail (both `+` card cases).
2. `+` always read as a space: 1 fails (IBAN case, `0+00` and `note=a+b`).
3. `+` pass only when the first pass changed nothing (the Codex 5 bug): 1 fails (mixed card and token).
4. Re-encode removed (round 1 finding): 2 fail.
5. Percent-decoding removed: 3 fail.
6. `+` pass result ignored: 2 fail.

## Rules checked
| Rule | Result |
|---|---|
| ST-ENG-05/15 | One private method with one use today; its Javadoc says why the `+` stays (Spring keeps a literal `+`) |
| ST-ENG-42/46/47 | No new static, lambda or constructor; `@RequiredArgsConstructor` on the filter unchanged |
| ST-ENG-56 | `QueryValueMasker` stays package-private; no new public type |
| ST-TEST-01/15/17 | New cases in the `@FieldSource` constant inside `@Nested QueryMasking`; each fails under a planted fault |
| ST-SEC-02/03 | No secret or personal data in tests beyond the fixed test cards and `example.com`; the untrusted URL path is unchanged |
| ST-AG-12 | No new class or file for the three fixes |

## Notes (not findings)
- Two card numbers in one value separated by spaces, `4111 1111 1111 1111 4222 2222 2222 2222`, leave the second card
  visible, on the span and in logs alike (`builtInRules` gives `****2222 2222 2222`). It is the shared card rule, not this PR, and
  not a realistic query. `token abcdefghijkl` without `=` is also unmasked in both.
- A masked value changes its encoding: `+` becomes `%20` and `%3A` becomes `:` in the masked form (`next=https://****@x.com/a?b%3D1`).
  Only masked values are affected; unchanged values keep their bytes.
- Spring normalises `?=abc&password=` to `?abc&password=****` and drops empty `&&`; harmless.
- The `+` pass doubles the rule cost per value; bounded by the 1,024-character span limit (96 µs measured).

## Not verified
- Sonar's detailed issue list (sonarcloud.io is unreachable from this session); I relied on the green check and the Claims' "0 new issues".
- The full reactor `mvn verify` at the head; I built the three changed modules with their dependencies. CI's Verify job is green.

Not merged.
