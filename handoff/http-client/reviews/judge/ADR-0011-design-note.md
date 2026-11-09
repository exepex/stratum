# Judge verdict: design note of ADR 0011 (HTTP client starters), Feature track, review step 1, 2, 3 and 5

Note: `design/http-client-libraries/adr-0011-design-note.md`. Source: `adr-0011-draft.md`, `design-v4.md`,
`gap-review-merged.md`, `spike-result.md`. Repo at `origin/main` 8b111f3. No branch or PR exists yet.

**FAIL.** One blocking finding and six should-fix findings. All are small text fixes; none reopens an owner decision.

I read AGENTS.md and all six topic files. Text-only change, so step 4 (build) is skipped. Nothing was changed in the repo.

## Gap list

| Requirement | Result | Evidence |
|---|---|---|
| ADR follows ADR 0010's format (Status, Context, Decision, Consequences, Follow-up) | PARTLY | Headings match. The Status line is still the draft's (F4). |
| Records the 21 owner decisions and the spike facts | MET | Each decision in `gap-review-merged.md` "Decisions" appears in the draft or in design-v4. Spike items match `spike-result.md`. |
| Three rule lines (ST-ENG-34, ST-SEC-03/02, ST-ENG-51) | PARTLY | Texts present; ST-ENG-51 wording has a defect (F3). |
| Consistent with existing rules | NOT MET | F1, F2. |
| Dependent files found (ST-AG-17) | NOT MET | F5, F6. |
| Eight ST-AG-14 parameters weighed | MET | All eight appear in the draft's Consequences. |
| Within size limits | MET | Draft 204 lines (limit 500). `engineering.md` 336, `security.md` 81, `docs/README.md` 128 stay under 500. |
| Not over-engineered | MET | Every feature traces to an owner decision. |

## Findings

**F1. Blocking. Java path contradicts ST-ENG-34 and ST-ENG-24.** ADR decision 1.4 says "a `StratumClientCustomizer`
*bean* wins over YAML", and the note says "The Java customizer is `StratumClientCustomizer`". ST-ENG-34
(`engineering.md:199`) says each module's settings go through `<Module>PropertiesCustomizer`, a class listed in
`META-INF/spring.factories`, and ST-ENG-24 says that kind is "never a bean". `StratumCustomizers.rejectBeans` stops
startup with `SX-CORE-1004` when one is a bean. The proposed ST-ENG-34 exception text covers only where the keys
live, not this. Fix: state in the ADR that every setting of a client is also set through an
`HttpClientPropertiesCustomizer` (the ST-ENG-34 mechanism, which still works with a `clients.<name>` map), and say
what `StratumClientCustomizer` is for (per-client builder hooks, a bean like Spring's `RestClientCustomizer`, as
ST-ENG-24 allows). Fix the note's section 2 the same way.

**F2. Should fix. ST-SEC-17 is not addressed.** ST-SEC-17 (`security.md:52`): "A URL that must come from input is
checked against an allow-list first." Decision 3.5 says Stratum does not check the host of a `URI` parameter. The
owner's decision is not in question, but the ADR must say how it sits with ST-SEC-17: Stratum does not enforce it,
and a service whose URI comes from input still must (the owner's words were that the team's code is the team's).
Without that line, the next judge of the H1 rework meets an unexplained conflict. No rule change is needed.

**F3. Should fix. ST-ENG-51 wording is wrong in two places.** Proposed text: "A request the server rejected
before doing any work, such as one never sent or a 401 to an expired service token, may be sent once more."
(a) "Never sent" is not "rejected by the server". (b) "Sent once more" contradicts ADR 4.2, which retries a POST
that was never sent (connect failure) up to `retries` times, 3 by default. Suggested: "A write that was never sent
(a connect failure) or that the server rejected before doing any work (a 401 to an expired service token) may be
retried; that is not a retried write (ADR 0011). The 401 is retried once." Same fix in the AGENTS.md summary.

**F4. Should fix. The draft's Status block is not repo-ready.** It says "Draft for the owner's review... Not in
the repository yet" and "changes two rules"; the ADR carries three rule changes. ADR 0010 reads "Accepted,
<date>, by the owner. Not built yet..." and lists what it replaces or changes. The coordinator must confirm the owner
accepted the ADR itself (the card approved opening the PR) before writing "Accepted". The note also says "two rule
lines" in the card but "three" in the text; use one count.

**F5. Should fix. Dependent-file search is wrong and incomplete (ST-AG-17).** The note says the command found 9
lines. I ran `git grep -n "ST-ENG-34\|ST-SEC-03\|ST-SEC-02\|ST-ENG-51" -- AGENTS.md docs/agents docs/adr` and got 14
(adr 0010:2, documentation:1, engineering:5, judge:1, review:2, security:2, testing:1). The note does not list the
ones that stay unchanged with a reason (`judge.md:107` "a Spring Boot key first" and `review.md:71` need at least
a pointer to the ADR 0011 exception, or a reason they stay). It also misses these dependents:
- `docs/modules/resilience.md:59-72,192`: shows `clients.payments.base-url` and `resilience-policy` on an HTTP
  client, and says `stratum.resilience.enabled=false` makes HTTP clients single-attempt. Decision 4.1 (Resilience4j
  inside the client starters, one retry layer) overlaps this page and the ADR never mentions the resilience
  starter. Add one line to the ADR on how the two relate (owner's 11-07 decision: Resilience4j inside the client
  starters), and add this page to "Documents updated" with a pointer or a rewrite (ST-DOC-01, ST-DOC-08).
- `docs/guides/architecture.md:19`: the same summary as AGENTS.md:231 ("retries only for idempotent operations").
- `starters/stratum-starter-observability/README.md:111`: keeps the full URL, query string included, on spans.
  Decision 8.2 only says "span names" use the template. Say whether the span URL attribute stays (and under which
  rule), so ST-SEC-03's new exception does not read as covering only logs by accident.

**F6. Should fix. "Not verified" omits the reactive stack.** The spike covered the MVC starter only
(`spike-result.md`, "Not verified"). The ADR says the reactive starter has "the same keys and rules on Reactor
Netty", while stale check, `connection-lifetime`, the pool binder and retry-off are HC5-specific, and the earlier
Boot 4.1.2 WebClient group bug is on record (project memory, H1). Add "the reactive stack" to the ADR's Not
verified list (ST-AG-01). Also add that HC5's own retry on 429 was not tested (moot with retry off, but listed in
the spike).

**Minor**
- M1. `judge.md` "The design note": "Each answer is at most 3 lines." Answers 1 to 4 run about 9 to 12 lines each.
- M2. "19 keys": the list has 20 entries if `url` and `urls` count separately (design-v4 says 19 the same way), and
  `api-key-header` is a 21st key under `auth`. Say "19 keys, `url` or `urls` counted as one".
- M3. The "alternative not chosen" (amending ADR 0006) is weak. The real alternative is keeping Boot's
  `spring.http.serviceclient.*` keys, as PR #79 does; it is covered only in the keys bullet. Name it as the
  alternative.

## Planted faults

Not applicable to a text-only note. Instead I checked by search: "ST-SEC-17" (found, F2), "resilience-policy"
(found, F5), "PropertiesCustomizer" (found, F1), "SX-HTTPC" (no existing use; codes follow the four-digit module
scheme of `docs/guides/error-codes.md`, fine).

## Notes (theoretical, not findings)

- A URL host that is an IP address could fall under ST-SEC-03's "IP addresses"; the exception text covers it.
- Spike ran on JDK 21, not 25; the note already says so.

## Not verified

Library behaviour (Boot, HC5, Resilience4j) was taken from `spike-result.md`, not re-run. I did not check the
owner's decisions against the chat itself, only against `gap-review-merged.md`. Not merged; no PR comment written.
