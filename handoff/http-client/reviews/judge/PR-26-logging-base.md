# PR #26, `claude/project-thread-8eutxb`, head `00e128c`

**FAIL.** Three blocking leaks or stalls in redaction, one owner decision missing. I read AGENTS.md and all six topic files.

Build: JDK 25.0.4.1, `./mvnw clean verify` at 00e128c → BUILD SUCCESS (logging: 57 unit, 7 integration, 0 failures). Probes ran through real Logback in ECS, Logstash and GELF; all three gave the same results.

## Gap list
| Requirement | Status | Evidence |
|---|---|---|
| Off by default (`stratum.logging.enabled`) | MET | `LoggingSettings.isEnabled`; fault F02 caught |
| ECS default; logstash, gelf, text | MET | F09 caught; probes in all three JSON formats |
| `service.name` and SX-LOGGING-1001 | MET | F19 caught |
| `service.environment` from `stratum.environment` | MET | F11 caught |
| Stack trace limit, root first | MET | F10 and F21 caught |
| Secrets, cards and IDs always masked, with no off switch | PARTLY | No switch exists, but compound names leak (finding 1) |
| Every JSON field redacted: message, error, MDC, key-value pairs | PARTLY | Non-text key-value pairs are skipped (finding 2) |
| Headers, URLs, query strings, key=value text | PARTLY | `Authorization`, `Cookie`, query strings and URL credentials are masked; `X-CSRF-Token` leaks (finding 1) |
| Fails closed | MET | An unexpected NPE planted in `redact` made Logback drop the line and print "Appender [CONSOLE] failed to append". The secret was not written. |
| Size cap before regex | MET, but see finding 3 | 1 MB input takes 0.6 ms because it is cut first; F01 caught |
| Bad team pattern fails startup (SX-LOGGING-1002) | MET | F04 caught; the error message does not include the pattern |
| WARN for text format outside local and test | MET | F03 and F20 caught; WARN seen in the build log |
| Only commons-validator and libphonenumber added | MET | `dependency:tree`; brings in commons-logging 1.3.6, which Spring also uses |
| Local/test redaction switch (agreed on the 20:58 card) | NOT MET, undecided | Dropped by the implementer; owner has not agreed (finding 4) |
| `*IntegrationTest` rename | MET | Failsafe and Surefire config, ST-TEST-30 |
| Scope is PR 1 only; docs | MET | Nothing from PRs 2 to 4; `logging.md` marks planned items. The doc's claim "every value" is wrong (finding 2) |

## Findings
1. **Blocking, ST-SEC-02/03.** `SensitiveFieldNames.mask` only matches exact names. These were all written in clear text: `dbPassword=`, `userPassword=`, `newPassword=`, `secretKey=`, `apiToken=`, `authToken=`, `aws_secret_access_key=`, `passphrase=`, `X-CSRF-Token:`, MDC `db_password`, and `dbPassword=` inside a stack trace and inside a record's `toString`. **Fix:** after normalizing, match a name that *contains* `password`, `passwd`, `secret`, `token`, `apikey`, `credential`, `privatekey`, `passphrase`, `cookie` or `authorization`. Keep exact matching for short names (`pin`, `otp`, `pan`, `auth`, `pwd`). Add tests for the leaked names above.
2. **Blocking, ST-SEC-02/03.** `Redactor.redact` `default` branch checks a non-String value only by its name. `addKeyValue("customer", customer)` wrote `Customer[..., dbPassword=hunter2i, email=x@y.com]`, and `addKeyValue("ref", 4111111111111111L)` wrote the card number. Maps and lists were masked correctly. **Fix:** redact `toString()` of any other value as text, and return the original value only when nothing changed. Add an integration test, and correct "every value" in `logging.md`.
3. **Blocking, ST-ENG-54/25.** Some rules are quadratic inside the 16 KB cap. Per value: `"a"*8000+"@"+"a."*4000` takes 1,668 ms, `"a."*4000+"@"+"b-"*4000` 1,495 ms, `"eyJ"*5000` 831 ms and `"a="*8000` 594 ms. Plain text takes 1 ms. Redaction runs on the caller's thread under the appender lock, so one hostile logged field (an email, a header) stalls every logging thread. **Fix:** make EMAIL_SHAPE, JWT and KeyValuePairs.KEY linear. Use a lookbehind so a match can't restart inside a run (`(?<![A-Za-z0-9._%+-])`), plus bounded possessive quantifiers. Add a test that each of these inputs is redacted quickly.
4. **Should fix, owner decision.** Aashish approved "no off switch outside the local and test profiles". The PR drops the local/test switch, citing the `text` format, so local JSON can never be seen unmasked. This is stricter and simpler, and I would accept it, but it changes his approved design. **Fix:** get his OK, or add the switch.
5. **Minor, ST-TEST-01.** Fault F08 (removing `cookie` from SECRETS) survived. The name list isn't tested name by name. **Fix:** test every built-in name in one parameterized test.
6. **Minor.** These leak: `{\"password\":\"x\"}` (escaped JSON in text), `{'password': 'x'}`, and `password%3Dx`. The 16 KB cut can leave a partial JWT (`eyJhbGciOiJIUzI1NiJ9.eyJlbWFp...`), whose header and part of the claims stay readable. **Fix:** at least accept single quotes, and mask a JWT with two or more parts.

## Planted faults
22 planted, 21 caught. F08 (`cookie` removed) survived. The others removed the cut, forced the customizer on, removed the WARN, the startup pattern check, the JWT, auth-scheme, URL, phone, SSN, IBAN and email rules and extra-keys, and changed the default format, root order, ECS environment, max range, Luhn check, name check, local-profile check, stack trace limit and quoted values.

## ST-ENG rules
| Rule | Result |
|---|---|
| 05 simplicity, 15 Javadoc | OK: small types, short Javadoc |
| 14 Javadoc everywhere, ST-TEST-12 DisplayName | OK: the build checks Javadoc; every test has a DisplayName |
| 20–22 auto-config, 21 @ConditionalOnStratumModule | Not applicable: no beans. Uses the same `stratum.logging.enabled` key from core |
| 30 record with @Builder | OK |
| 31 validation | max-field-length is checked by hand (no DataSize constraint); OK |
| 34 Java config | Documented exception (logging starts before beans); owner aware via the PR notes |
| 39/40 records, var, switch patterns | OK |
| 41 @NullMarked | OK: all 4 package-info files |
| 42 Lombok, 43 builders, 44 enum | OK: @UtilityClass, @RequiredArgsConstructor, @Getter; `LogFormat` enum |
| 45 constants, 46 lambdas, 47 static | OK |
| 54 cheap hot path | FAIL (finding 3) |
| 62 new deps justified | OK: in the PR |
| AG-10 line counts | OK: largest is README.md at 451; code at most 340 (build-parent pom) |

## Notes
- **00e128c test fix is sound.** Spring Boot leaves the console format in a JVM system property. In production there is one app per JVM, so only tests that start several apps in one JVM are affected. Clearing the property before the module-off test fixes the cause; it is not a mask.
- **Fail closed.** Only three exception types are caught. Any other exception drops the line rather than writing it, and Logback stops reporting such errors after a few. There is no leak, so this is not a finding.
- Plain-text *file* logs (`logging.file.name` without a structured format) are not redacted and get no WARN. Consider adding that to the docs.

## Round 2

**PR #26 at head `d31ba11`: FAIL.**

The full `./mvnw clean verify` passes on JDK 25: 144 unit tests and 9 integration tests in the logging module, no failures.

| Round 1 finding | Result | Evidence |
|---|---|---|
| 1. Compound secret names | Fixed | Re-ran the probe through Logback (ECS). `dbPassword`, `apiToken`, `aws_secret_access_key`, `X-CSRF-Token`, MDC `db_password`, stack trace and `toString` values are all masked. Fault G01 is caught by 44 tests. |
| 2. Values that aren't text | Fixed | A `long` card number is written as `****1111`. Objects, `Object[]` and `Optional` are masked through `toString()`. Fault G02 is caught. |
| 3. Slow patterns | Partly fixed | Email: 1.7 ms. JWT: 0.8 ms. Key-value runs: 7.6 ms. Card digit groups are still slow (new finding 1 below). |
| 4. Local/test switch | Fixed | Aashish chose "No switch (as built)" on his 21:53 card. |
| 5. Every name tested | Fixed | Removing `cookie` is now caught (G09). |
| 6. Escaped, single-quoted and `%3D` keys; cut JWT | Fixed | Probes P09, P10, P16 and P28 are masked. The cut now drops a partly cut word. |

The log file path is also covered. With `logging.file.name` set, the file is written as ECS JSON, and the probe found no unmasked value in it (31 lines). Fault G05 is caught.

### Findings

1. **Blocking (ST-ENG-54): card digit groups are still slow.** In `ContentRules.longestCard`, every candidate joins its groups again (`digitsOf`) and calls `CreditCardValidator`.
   - `"0 "` repeated 8,000 times takes 440 ms for one 16KB value.
   - At the allowed 1MB limit, `"0 "` repeated 500,000 times takes 29 s, and `"1 "` takes 3.8 s.
   - **Fix:** build each candidate's digits by extending the previous one, not re-joining all groups. Before calling the validator, skip a candidate cheaply when it starts with `0` or fails Luhn (`LuhnCheckDigit`). Add `"0 "` and `"1 "` runs at 16KB and at 1MB to `SlowInputTest`.
2. **Should fix (ST-TEST-01): four planted faults survived.**
   - G04: the JWT lookbehind and possessive quantifiers were removed (no slow-input test).
   - G06: the cut was allowed to split a word.
   - G11: `Object[]` under a sensitive name was written unmasked.
   - H02: only the first card in a run of digit groups was masked, as in `4111 1111 1111 1111 4111 1111 1111 1111`.
   - **Fix:** add a test for each.
3. **Minor:** `pass=hunter2z` is not masked. **Fix:** add `pass` to the names that must match whole.

### Aashish's points

- **Loops and names:** no `for`, `while` or `do` loops are left in the module (grep). No one- or two-letter names are left in variables, lambdas or `catch` blocks.
- **Readability of the streams:**
  - The `Stream.iterate` scans in `KeyValuePairs` and `ContentRules.maskCards` read clearly, and they kept the same masking: the probe output is unchanged and faults H01 and H03 were caught.
  - `ContentRules.replace` reads less clearly than a loop: it uses an `IntStream` with index arithmetic and a joining collector that adds the tail. This is minor and not a finding.
  - `KeyValuePairs` uses one `Matcher` with state inside `Stream.iterate`. That is safe only because the stream is sequential.
- **Libraries:** Commons Validator and libphonenumber check the candidates; the patterns only find them. I found no library that finds secrets in free text.
- **Classes:** 9 main classes. The largest is `ContentRules` at 275 lines, under the 400 limit. Each class has one job.

### Planted faults

15 planted, 11 caught. Survivors: G04, G06, G11 and H02.

### Line counts

All files are within their limits. The largest is `README.md` at 451 lines; the largest Java file is `SecretRedactionTest` at 285.

## Round 3

**PR #26 at head `9df3a9c`: PASS.**

The full `./mvnw clean verify` passes on JDK 25. The logging module ran 164 unit tests and 9 integration tests, with no failures.

| Round 2 finding | Result | Evidence |
|---|---|---|
| 1. Slow card digit groups | Fixed | At 16 KB, each of `"0 "`, `"1 "`, `"9 "` and `"4-"` repeated now takes at most 26 ms, where `"0 "` took 440 ms before. |
| 2. Four uncaught faults (G04, G06, G11, H02) | Fixed | All four are caught when planted again (J01 to J04). |
| 3. `pass` | Fixed | `pass=x` is masked (P31). Fault J05 is caught. |

**Leaks.** I re-ran the full probe through Logback in ECS, with the log file on. All 33 probe lines are masked in both the console and the file, except `passCode=` (finding 1). That includes JSON arrays and objects under secret keys (P29), a card after `order123` and two cards in one run (P30), arrays and lists under secret names (P32), and Digest parameters (P33).

**URLs.** I tried 15 shapes. These are all masked: an empty user name, `+srv`, `jdbc:`, upper case, a percent-encoded user, two URLs in one value, and quoted or `key=` prefixed URLs. A password with a raw `@` or `/` in it is not masked (finding 2).

**Speed.** At the 16 KB default, every hostile input takes at most 62 ms (phone search). At the 1 MB maximum, the worst case is `"1 "` repeated, at 0.87 s. I accept that, for two reasons: 1 MB is something a team must choose, and the time grows in a straight line with the value's length. The module page could warn that large limits slow logging.

**Builders (ST-ENG-43).** `Redactor`, `Value`, `ContainerScan` (with toBuilder), `CardSpan`, `DigitGroups` and `Replacement` are built with Lombok `@Builder`. No constructor with three or more arguments is left in main or test code: the remaining `new` calls take one or two arguments (`Pair`, `SensitiveFieldNames`, `KeyValuePairs`). No `for` or `while` loops are left, and there are no short names.

### Findings (minor, do not block)

1. `passCode` and `passcode` are not masked. **Fix:** add `passcode` as a whole name.
2. A URL password with a raw `@` or `/` is not masked (`https://bob:p@x@host` leaves `x@host`). Such URLs are invalid, but they do show up in logs from misconfiguration. **Fix:** mask up to the last `@` before the host.

### Planted faults and line counts

7 faults planted, 7 caught. The largest file is `README.md` at 451 lines. The largest Java file is `SecretRedactionTest` at 333 lines, and the largest main class is `ContentRules` at 314. All files are within their limits.

## Round 4

**Head `6d8a201`: FAIL**, on one should-fix finding.

**Build.** The full `./mvnw clean verify` passes on JDK 25, with 168 unit tests and 9 integration tests and no failures. The probe through Logback (console and file) writes 36 lines, and all of them are valid JSON. Nothing leaks except the minor `passCode` case from round 3.

**Fix 1, JSON objects written as values: works.** I checked each case by calling `Redactor` directly:
- A plain object, a nested object (`{"a":{"b":{"token":…}}}`) and Boot's own `JsonWriter` output are all masked, and the result is written as a string.
- Clean JSON stays raw.
- Empty JSON writes nothing.
- JSON under a secret name becomes `"****"`.
- All three planted faults were caught (K01: JSON left unmasked, K02: second IBAN skipped, K03: masked JSON written raw).

**Fix 2, adjacent grouped IBANs: works.** Three IBANs in a row, in either compact or grouped form, are all masked, and so is a mix of GB and DE IBANs.

**Speed.** At 16 KB, the slowest IBAN input takes 38 ms. At 1 MB, `"de12 "` repeated takes 0.75 s, in line with the limit I accepted in round 3.

### Finding

1. **Should fix, ST-SEC-03: an IBAN is not masked when an IBAN-shaped word comes just before it.** For example, `ref DE12 DE89370400440532013000` and `AB12CD DE89 3704 0044 0532 0130 00` are both written in full.
   - **Cause:** the IBAN pattern takes the short word and the real IBAN as one candidate. That candidate is not a valid IBAN, so it is skipped, and the next search starts at the candidate's end, past the real IBAN.
   - **Not new:** this was already the case at 9df3a9c, so my round 3 PASS missed it.
   - **Fix:** after a candidate is rejected, search again from the next word inside it, not from its end. Add the two examples above as tests, and a slow-input case for runs of short IBAN-shaped words.

Minor items carried over from round 3: `passCode` is not masked, and a URL password containing a raw `@` or `/` is not masked.

## Round 5

**Head `2224c39`: FAIL**, on one should-fix finding. All leaks are closed.

**Build.** The full `./mvnw clean verify` passes on JDK 25: 171 unit tests and 9 integration tests, no failures. CI is green: Verify and SBOM, CodeQL and SonarCloud.

**IBAN fix works.** These inputs are now masked:
- `ref DE12 <IBAN>`, `AB12CD <IBAN>`, and `AB12 CD34 EF56 <grouped IBAN>`.
- Chains of `de12` words followed by two IBANs.
- Lower-case IBANs.
- IBANs mixed with grouped and compact cards, in either order.
- DE and GB IBANs next to each other.

When I planted fault L01 (resume the search at the candidate's end), it was caught.

**Speed at 16 KB.** Every IBAN-like input takes at most 51 ms.

**Speed at 1 MB, without JaCoCo.** The slowest input is `"ab12 cd34 "` repeated, at 1.1 s. `"de12 "` takes 0.73 s. Both grow in a straight line with input size.

### Finding

1. **Should fix (ST-TEST-14): the 1 MB SlowInputTest cases can flake in CI.** They run under JaCoCo with a 5 s `assertTimeoutPreemptively` limit.
   - In two runs one after the other, case [8] took 1.7 s and then 3.5 s. That is a 2x swing, leaving only 1.4x headroom on a 5 s limit.
   - A slower or busier CI runner can go over 5 s, and a red build would then block every later PR.
   - The limit only has to catch quadratic behaviour. The round 2 regression took about 29 s at 1 MB.
   - **Fix:** raise `LARGEST_LIMIT` to 15 s. It still catches that regression, and it gives about 4x headroom.

The minor items are unchanged: `passCode` is not masked, and a URL password containing a raw `@` or `/` is not masked.

## Round 6

**Head `5d7b5fb`: PASS.**

The full `./mvnw clean verify` passes on JDK 25: 172 unit tests and 9 integration tests, with no failures. The slowest 1 MB SlowInputTest case took 4.0 s under JaCoCo, well inside the new 15 s limit, so the flake risk from round 5 is gone.

**Cards.** I ran 25 card shapes, and every one that our rules treat as a card was masked:
- adjacent, overlapping and dash-joined cards;
- groups of four, and Amex in its 4-6-5 grouping;
- Discover, Visa and Diners in one line;
- a card between other numbers;
- a card inside a word (`x4111…y`).

Two shapes are left as they are, by design: two cards joined with no separator form one run of more than 19 digits, which can't be told apart from an ordinary number; and timestamps that fail the Luhn check stay readable.

Speed: at 16 KB, every card input takes at most 23 ms. At 1 MB the worst is `"9 "` repeated, at 0.9 s, which grows in a straight line with the input.

**CardNumbers class.** It has one job: finding and masking card numbers. It is 170 lines. Folding it back into `ContentRules` would take that file to about 430 lines, over the 400-line limit (AG-10). So this split follows the file-size rule and does not go against Aashish's request for fewer classes. It brings the module to 10 main classes.

**Planted faults.** 3 planted, 2 caught.
- M01 (overlaps removed) and M02 (Luhn check removed) were caught.
- M03 (pick the shortest valid card instead of the longest) survived. I tried it on the round's examples and it still masked every card; only the last four digits shown changed. So this is a note, not a finding.

**Minor, not blocking:**
- Card digits separated by `.`, `/`, `_` or double spaces are not found.
- `passCode` is not masked.
- A URL password that contains a raw `@` or `/` is not masked.
