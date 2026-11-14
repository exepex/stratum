# Parked: rule-book cleanup (do not merge)

Parked by the owner on 2026-11-02 to finish the logging starter first. This branch holds the work done so far, so it
is not lost when the session ends. Nothing here changes a rule yet.

## The problem

The same rule is written in more than one file. A fix changes one copy and misses the other, so every review finds
new gaps. The three final reviews of PR #115 found 10 such gaps:
[Sonnet](https://github.com/exepex/stratum/pull/115#issuecomment-6581644233),
[Opus](https://github.com/exepex/stratum/pull/115#issuecomment-6581657220),
[Fable](https://github.com/exepex/stratum/pull/115#issuecomment-6581737075).

## The agreed plan, bottom up

Each layer is proven before the next one starts.

- **Layer 0, map (in progress):** list every rule, where it is defined, cited and copied. Changes nothing.
- **Layer 1, guard:** a build check that fails when a rule ID is defined in more than one file. Needs the owner's go.
- **Layer 2, rule:** "each rule is written in full in one place only; every other file cites its ID", plus a map of
  which file owns which topic.
- **Layer 3, sweep:** one file per pull request, replacing copies with ID pointers. Home files first, `AGENTS.md`
  last.
- **Layer 4, remaining gaps:** clashes between different rules, one per pull request.
- **Layer 5, final check:** three reviewers once; fix blocking and should-fix in one batch; minor ones to one issue.

## Layer 0 status at main 54f4ce1

| Step | Result | Proof | Files |
|---|---|---|---|
| 0.1 rule IDs | 119 bold definitions plus ST-AG-10 in a heading; none defined twice | judge PASS | `l0-ids.tsv` |
| 0.2 citations | 178 citations in 33 files; 11 range citations; 26 rules cited nowhere | judge PASS in round 2 | `l0-citations.tsv`, `l0-ranges.tsv` |
| 0.3a `AGENTS.md` | 107 rules with no ID, most copied elsewhere | two lists plus a decider | `l03a-*.md` |
| 0.3b `judge.md` | 123 rules with no ID, 84 copied elsewhere | two lists plus a decider | `l03b-*.md` |
| 0.3c `review.md` | not started | | |
| 0.3d other four topic files | not started | | |
| 0.4 copies in every file | not started | | |

## Clashes found so far

`AGENTS.md` (0.3a):
1. Spring Boot version "set once, in `stratum-parent`" (line 21); ADR 0005 and `pom.xml:20` put it in the root POM.
2. "No abstraction without a second use" (line 181); ST-ENG-05 says a use that exists today is enough.
3. "Every class, method ... has Javadoc" (line 196); ST-ENG-14 and ST-TEST-41 exempt tests.
4. "Constructors only" (line 197); ST-ENG-26 allows field injection in test classes.
5. Wording only: line 12 says `AGENTS.md` is "the only place" rules are written; they also live in `docs/agents/`.

`judge.md` (0.3b):
1. "Pushes nothing" until a PASS (34-35); Rounds (204-206) need a push after a FAIL; `AGENTS.md:235` is correct.
2. Unrequested work is should-fix (135); `review.md:14` makes a broken `AGENTS.md` rule (ST-AG-06) blocking.
3. No PASS with an open should-fix (168-169); `review.md:15` lets it stay "unless the owner accepts it".
4. With no coordinator the implementer "may do" its duties (50-51); `AGENTS.md:99` says "does".
5. An unused type is should-fix (145-147); `review.md:14` with `AGENTS.md:180-181` makes it blocking.
6. Copied logic is should-fix (150-151); `review.md:14` with `AGENTS.md:182-183` makes it blocking.
7. Every dependent file "was updated" (153-154); `AGENTS.md:136-138` also allows "named with the reason it stays".

Items 2, 5 and 6 need one owner decision: does `review.md:14` "a broken rule in AGENTS.md" cover the "Engineering
rules" summary bullets?

## Other notes

- ST-ENG-65 is cited in ADR 0001:58 but was removed by ADR 0002:28.
- ST-AG-10 is defined only by a heading; the Layer 1 guard must handle that.
- Rule IDs are cited in build files (Checkstyle, PMD, POMs), so IDs are never renumbered.
