# Judge verdict: C11 design note (reactive parity and stampede tests), round 1

Design note `design/cache-c11/c11-design-note.md`, base `main` at `e858868`, Feature track, design-note review.
Judge model: Fable 5.1 (implementer: Opus 5.5). Rules read: AGENTS.md and the six topic files.
Sources checked by the judge: Spring Framework 7.0.10 and Caffeine 3.2.4 source jars (versions from
`spring-boot-dependencies-4.1.2.pom:41,196`), Spring Data Redis 4.1.2, and the starter at `e858868`.

**Verdict: PASS.**

## Gap list (plan rev 7, C11; rows S2 and S4)

| Requirement | State | Evidence |
|---|---|---|
| `retrieve` keeps fail-open | MET already | Spring reports every retrieve failure to the handler: `CacheAspectSupport.java:471,544,1163,1181,1255`, `AbstractCacheInvoker.java:121-147`; the handler is Stratum's (`StratumCacheLayerHook.java:76-83`) |
| `retrieve` keeps run-once | Designed | Gaps 1 and 2 are real: a null or a failed SET fails the future (`RedisCache.java:298,305,310-317`) and Spring re-invokes the method (`CacheAspectSupport.java:466-476`, `1178-1186`). `StratumCache.retrieve` recording the loader's future, modelled on `StratumCache.get` (`StratumCache.java:66-91`), closes both |
| `retrieve` keeps the null skip | Designed | Redis: as above. Caffeine: a refused null put fails the future, turned into null in `StratumCache`, one rule in one place |
| Typed reads for `CompletableFuture`, `Mono`, `Flux` | MET already | `TypedCache.java:96,111` (C8) |
| Caffeine async mode: detection and effect on `sync` callers settled | MET | Async mode off, Stratum serves `retrieve`. Verified: without async mode `CaffeineCache.retrieve` throws `IllegalStateException` (`CaffeineCache.java:122-126,139,147-153`); with it, the blocking `get(key, loader)` goes through the synchronous view to `supplyAsync` on the cache executor (`LocalAsyncCache.java:79-83,610`), `ForkJoinPool.commonPool()` by default (`Caffeine.java:374`), and every failed async load is logged WARN with its stack (`LocalAsyncCache.java:205-209`). A team that turns async mode on keeps Caffeine's own `retrieve` |
| Tests: outage and null on reactive methods; 100 concurrent `sync=true` misses load once on Caffeine | Planned in the note | Section 4, each with a planted fault matching the plan's two. A plain Boot test application is enough: Spring's reactive path keys on Reactor's `ReactiveAdapterRegistry`, not on WebFlux (`CacheAspectSupport.java:1148-1153`) |
| No Redis lock (Q5) | MET | Stated as a limit, nothing built |
| Hostile input: a large `Flux` collected whole | Stated | `CacheAspectSupport.java:1157-1159`; stated as a limit, bound by C5's value limit on Redis |
| Docs: stampedes and reactive methods | Planned | New `cache-reactive.md` because `cache.md` is at 476 lines (`wc -l`); `cache-null-results.md:18-21` and `cache.md:414` updated |

The note answers all four questions in the required form; no "none" that the design contradicts.

## Findings

None.

## Notes (theoretical or precision only; nothing to build)

1. Gap 3 says Caffeine reactive methods are "never cached". Precisely: the `put` after the method stores the value,
   but Spring's reactive lookup treats a `retrieve` failure as a miss and never falls back to `get(key)`
   (`CacheAspectSupport.java:1245-1248`, `559-562`), so the value is never served. Same effect; wording only.
2. The WARN "with the key's text" in async mode comes from the blocking `sync=true` path, where `LoadFunction` wraps
   the refused null in `ValueRetrievalException` (`CaffeineCache.java:231`). A reactive null in async mode completes
   without an error and logs nothing (`LocalAsyncCache.java:205-209`). The thread hop alone rules async mode out.
3. `CaffeineFutureCache.retrieve(key)` on a miss: return `null`, as Caffeine's own `getIfPresent` does, not a
   completed future of null. Spring handles both (`CacheAspectSupport.java:1245-1248`); null is cheaper.
4. The known edge of a team handler that rethrows put errors: if it rethrows puts but swallows gets, the method also
   runs twice (`CacheAspectSupport.java:471-475`). Theoretical; the note's limit line can say "may run twice".
5. Not verified by the judge: that Reactor is on the starter's test class path (it comes with Lettuce). The
   implementer's build will show it.
