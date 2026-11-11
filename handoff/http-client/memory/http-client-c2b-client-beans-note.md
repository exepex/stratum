---
name: http-client-c2b-client-beans-note
description: C2b redesign (injectable RestClient/RestTemplate beans per client) design-note judge r1 FAIL (Opus) and r2 FAIL (Fable) 2026-11-09, findings and probe facts
metadata:
  type: project
---

C2b redesign note design/http-client-libraries/c2-own-clients/c2b-design-note.md.
r1 judge (Opus 5.5) FAIL: reviews/judge/C2b-client-beans-note.md. r2 judge (Fable 5.1, ~18:40Z) FAIL on 3 should-fix,
all 1-2 lines in the note: reviews/judge/C2b-client-beans-note-r2.md. Two rounds done -> owner decides.
Owner 18:10Z: support BOTH RestTemplate and RestClient beans.

r2 findings: (1) RestTemplate must take the wrapped factory via RestTemplateBuilder.requestFactoryBuilder(settings -> f),
not requestFactory(Supplier): Boot's RestTemplateBuilder bean carries spring.http.clients.* HttpClientSettings and
applies them reflectively to a supplied factory (probe: read-timeout=10s silently set the HC5 factory 5000->10000;
connect-timeout / redirects -> IllegalStateException at build). (2) Beans give one span per call (retries inside
execute(); observation starts before execute in DefaultRestClient and RestTemplate), interfaces one per attempt: state
in docs. (3) Own-engine client (ADR 0011 9.3 :161 promised .resilience) must be declared out of scope + issue in
ADR 0013. Minors: RestTemplateBuilder has no requestInitializer method (use a RestTemplateCustomizer adding to
getClientHttpRequestInitializers()); pom.xml:38 comment and docs/README.md ADR row stale; OwnClientsIntegrationTest
205 lines, plan a new class.

r1 facts (Spring 7.0.10/Boot 4.1.2): qualifier + setDefaultCandidate(false) works for ctor, setter,
qualifiedBeanOfType; getBean(Class) still finds it; team @Qualifier("partner") bean wins over ours;
AbstractHttpServiceRegistrar.registerBeanDefinitions is final (needs a second registrar); request-factory wrapper
streams 400 MB at -Xmx64m; RetryRules counts all 5xx+429 against the breaker.

Related: [[http-client-lane-c]] [[http-client-c2b-resilience-note]] [[judge-build-environment]]
