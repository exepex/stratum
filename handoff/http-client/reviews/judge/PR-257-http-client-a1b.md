# Judge verdict: PR #257 (A1b, service token and token relay), gate

PR #257, branch `claude/project-thread-8ivbzo`, head `c1e111e`, track Feature, review step: code (gate).

**FAIL at the gate.** Under judge.md step 1, a gate FAIL has one finding and no further checks. It does not count as
a round, so the next review of this step is round 1.

## Gap list

Not built. Gate checks done:

- Open and ready for review (not a draft): MET (`draft: false`).
- CI green at `c1e111e`: MET. Verify and SBOM, CodeQL (both analyses), wiki check and SonarCloud all `success`.
- Review-bot threads answered and resolved: MET. 8 Codex threads, all resolved. Codex's code review at `c1e111e`
  reports no findings. Sonar's quality gate passed with 1 open issue (S1118), answered in a PR comment.
- Claims complete and the branch current with `main`: NOT MET (Finding 1).

## Findings

1. **Blocking: AGENTS.md ST-AG-17 and judge.md "The Claims" items 5 and 7.** The branch conflicts with `main`, and
   the Claims are incomplete.
   - **Conflicts.** `git merge-tree --write-tree origin/main pr257` exits 1 with content conflicts in 7 files:
     `HttpClientErrorCode.java`, `ClientConnectionPools.java`, `RestClientEnvironmentPostProcessor.java`, the starter
     `pom.xml`, `docs/guides/error-codes.md`, `docs/modules/http-client.md` and `docs/README.md`. GitHub reports
     `mergeable_state: dirty`.
   - **Cause.** #258 merged at 19:41:02Z, one minute after `c1e111e` was pushed (19:40:05Z). The base was `ba0e42b`.
     The implementer is not at fault, but resolving these conflicts changes code that this PR's behaviour depends
     on: the error codes, the pool timeouts and the status defaults. A PASS at `c1e111e` would not hold at the head
     that can actually be merged.
   - **Claims gaps.** The self-review gives no rebase evidence and no read of CI and the bots at the final head. The
     Claims also have no "Files read" line saying the implementer read AGENTS.md and the six topic files.
   - **Fix.** Rebase on `main` at `abeaa2a` or later and resolve the 7 conflicts. Run
     `./mvnw -pl libs/stratum-http-client,starters/stratum-starter-oauth2-client,starters/stratum-starter-http-client -am verify`.
     Wait for CI, Codex and Sonar at the new head. Then update the Claims with the rebase, the build result, the
     bot results at that head, the "Files read" line and the line counts of the files the merge changed.

## Planted faults

None planted; the gate failed.

## Notes

- The coordinator asked for two checks. The round-1 judge at the rebased head should make both, because the
  conflict in `ClientConnectionPools.java` touches the token timeout path:
  - The Codex P2 answered as theoretical: a renewal that joins another renewal resends the same refused token
    (`SingleFetchTokenManager.java:134-136`).
  - The Sonar S1118 answer: a private constructor conflicts with PMD's
    `MissingStaticMethodInNonInstantiatableClass`.
- The PR body's attribution line has no project-thread link. This is not a rule finding.

Judge: independent code judge. The configured model is claude-opus-5-5; the model that actually served this
session may differ. I read AGENTS.md and all six topic files.
