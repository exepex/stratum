# C10 Caffeine design note, round 1, Feature track, note review (steps 1, 2, 3, 5)

Note: `design/cache-c10/c10-design-note.md`, base `main` 28d22ce. Assumes option A (expire after 10 m). Judge: Sonnet 5.5.

**FAIL** (3 small findings, no design flaw; fix the note and go on)

## Gap list

| Requirement (plan C10) | Verdict | Evidence |
|---|---|---|
| Bounded size, safe default | MET | `@Fallback Caffeine` bean fits Boot's order spec > `CaffeineSpec` > `Caffeine` bean (`CaffeineCacheConfiguration.setCacheBuilder`, spring-boot-cache 4.1.2) |
| Stats | MET | `recordStats` in the same bean; `counters.bindCacheMetrics` already binds on the raw manager cache (`StratumCacheResolver.wrap`) |
| Per-cache size | MET | `Policy.eviction()` is optional, `isWeighted()` and `setMaximum` exist (Caffeine 3.2.4 `Policy.java:96,164,206`); static name list returns null from `getCache` (`CaffeineCacheManager.setCacheNames` sets `dynamic=false`), so `SX-CACHE-1008` is needed and right |
| Null skip | MET | `CaffeineCache.LoadFunction` wraps the refusal in `ValueRetrievalException`, which `StratumCache.get` rethrows first, so the fix is real; `rejectsNulls` already covers `CaffeineCache`; `RecordedLoader.hasRun()` is set after the loader returns |
| Four design-note questions | MET | all four answered, with `file:line` |
| No package cycle, API vs `@StratumInternal` | MET | same pattern as `RedisCacheWriterAutoConfiguration`; `SX-CACHE-1008` is free (1001 to 1007 used) |
| Null rule order | MET | `setAllowNullValues` refreshes all caches (`CaffeineCacheManager:236-240`), so running it first and the per-cache customizer last is correct |

## Findings

1. **Should fix, question 1 is not true under A.** §1 lists three changes for a service already on Caffeine (bounded, stats, no nulls). Under A there is a fourth: its entries now expire after 10 m. State it, with the opt-out (own `spring.cache.caffeine.spec` without `expireAfterWrite`), in §1 and in `cache-caffeine.md`.
2. **Should fix, documents list is incomplete under A (ST-AG-17 search).** Redis-only wording stays in: `docs/modules/cache-per-cache.md:9,15` ("lives in Redis"); `StratumCacheProperties.java:220-221` (`timeToLive`: "this Redis cache", "Defaults to spring.cache.redis.time-to-live"; for Caffeine the default is the builder's 10 m); `CacheTimeToLiveCustomizer.java:11`; `additional-spring-configuration-metadata.json:6` and `cache.md:121` (`stratum.cache.enabled` says it sets no "defaults for Spring Boot's Redis cache and client"). Add them to §4 and run the search.
3. **Minor, name.** A nested record `StratumCacheProperties.Caffeine` clashes with `com.github.benmanes.caffeine.cache.Caffeine`, which `CaffeineCacheAutoConfiguration` imports in the same file as `properties.caffeine()`. Name it `CaffeineSettings` (as `Serialization`, `CacheSettings`).

## Planted faults

Not applicable to a note.

## Notes (not findings)

- With Redis and Caffeine both on the class path, Boot picks Redis (note cites `CacheConfigurations:32-33`), so `max-entries` is ignored without a message. One line in `cache-caffeine.md` is enough.
- The "Overlap" paragraph is right: C9 and the C6 follow-up edit `cache.md` and `CacheSettings`; rebase on the later merge.

---

# C10 Caffeine design note, round 2, Feature track, note review (steps 1, 2, 3, 5)

Note: `design/cache-c10/c10-design-note.md` (round 2 text), base `main` 28d22ce. Assumes option A (expire after 10 m) while the owner decides. Judge: Fable 5.1 (a different model from round 1).

**PASS**

## Round-1 findings

| # | Verdict | Evidence |
|---|---|---|
| 1 (fourth change under A) | FIXED | §1 now lists "four under A": bounded, stats, no nulls, 10 m expiry, with the opt-out (own `spring.cache.caffeine.spec` without `expireAfterWrite`, or own `Caffeine`/`CaffeineSpec` bean) and says `cache-caffeine.md` repeats it |
| 2 (Redis-only wording) | FIXED | §4 "Round 2" lists `cache-per-cache.md:15`, `StratumCacheProperties.java:220-221`, `CacheTimeToLiveCustomizer.java:11`, `additional-spring-configuration-metadata.json:6`, `cache.md:121`; I re-ran the note's grep on 28d22ce and each line is there as described (see Notes for one more hit) |
| 3 (`Caffeine` record name) | FIXED | §1: nested record is `CaffeineSettings` |

## Gap list

| Requirement (plan C10) | Verdict | Evidence |
|---|---|---|
| Bounded size, safe default | MET | Boot 4.1.2 `CaffeineCacheConfiguration.setCacheBuilder`: spec, then `CaffeineSpec` bean, then `Caffeine` bean (source jar, read); a `@Fallback` bean fills only the last slot, so every Boot route wins |
| Stats | MET | `recordStats` in the same bean; `StratumCacheResolver.java:125` already binds metrics on the raw cache |
| Per-cache size (and A: time to live) | MET | Caffeine 3.2.4 `Policy.java:96,125,164,206,430` (`eviction()`, `expireAfterWrite()`, `isWeighted()`, `setMaximum`, `setExpiresAfter(Duration)`), read from the source jar; `CaffeineCacheManager.getCache` creates a cache only when `dynamic` (`:252-257`), so a fixed name list needs `SX-CACHE-1008`; 1008 is free (1001-1007 used, grep) |
| Null skip | MET | `CaffeineCache.LoadFunction.apply` wraps `toStoreValue`'s refusal in `ValueRetrievalException` (`CaffeineCache.java:229-235`); `StratumCache.get` rethrows it before the null check (`StratumCache.java:71-73,80-83`), so moving the check first is the right fix; `rejectsNulls` (`StratumCacheResolver.java:133-135`) already covers `AbstractValueAdaptingCache`, which `CaffeineCache` extends |
| Customizer order | MET | `CacheAutoConfiguration.java:56` uses `orderedStream()`; `setAllowNullValues` refreshes all caches (`CaffeineCacheManager.java:236-240`), so HIGHEST for the null rule and LOWEST for the per-cache settings is correct |
| Four questions, keys, Java route, cycles, API vs `@StratumInternal` | MET | all four answered within the rule; keys follow Boot-first; `cache` gains no import of `autoconfigure`; same shape as `RedisCacheWriterAutoConfiguration` |
| Documents and line counts | MET | `cache.md` is 495 lines (wc), so moving "Null results" out is needed; new `cache-caffeine.md` and `cache-null-results.md` listed |
| Security (step 5) | MET | no untrusted input; configuration only; not on the trigger list |

## Findings

None.

## Planted faults

Not applicable to a note.

## Notes (not findings)

- The note's own grep also hits `docs/modules/cache.md:130` (`time-to-live` row: "lives in Redis"), which §4 does not list. The note says the search is rerun in the Claims, which catches it; the code judge checks that row under A.
- `@Fallback` together with `@ConditionalOnMissingBean(Caffeine.class)` is redundant (either alone gives the service's bean the slot), but it matches `RedisCacheWriterAutoConfiguration.java:74-75`, so keep the pattern.
- Under option B the per-cache `time-to-live` stays Redis-only and findings 1 and 2 shrink to the size and null changes; nothing else in the design moves.
