# C2b options: retry and circuit breaker for clients Stratum did not build

**Question (Aashish, 2026-11-09 18:00Z):** the explicit wrapper `stratumHttpClients.resilience("partner").call(...)` adds
calls to the team's code. Is there a way that fits the Spring Boot way of working better?

**Who needs it:** a generated client that takes `stratumHttpClients.restClient("partner")`. A client's interfaces
(`@StratumClient`, `interfaces`) already get retry and breaker from B1 with no code.

**What any option must keep (ST-ENG-51, B1):** one breaker per downstream, shared with the client's interfaces; never
resend a `POST` after it was sent; never send a streamed body twice; `total-timeout` per call; settings under
`stratum.http-client.clients.<name>` in YAML and Java.

## Option 1: Spring Framework 7's own `@Retryable` (no Stratum code)
```java
@EnableResilientMethods            // once, in the service
@Retryable(maxRetries = 3, delay = 200, includes = ResourceAccessException.class)
public Order order(String id) { return partnerOrders.getOrder(id); }
```
- Facts (spring-context 7.0.10 jar, checked): `org.springframework.resilience.annotation` has `@Retryable`,
  `@ConcurrencyLimit` and `@EnableResilientMethods`. There is **no circuit breaker** in Spring.
- It retries on the exception types the team lists. It cannot tell a `POST` that was sent from one that was not, so it
  can resend a payment. It has its own settings on the annotation, not the client's `retries`, `retry-delay` or
  `total-timeout`, and no breaker.
- Spring changes: none for Stratum, since Stratum adds nothing. Each team carries the risk instead.

## Option 2: a Stratum annotation on the team's method, backed by the client's own retry and breaker
```java
@StratumResilience(client = "partner")
public Order order(String id) { return partnerOrders.getOrder(id); }
```
- A Spring AOP advice runs the method through `ClientResilience.call`. That is B1's code, so you keep one breaker
  shared with the interfaces, the verb rules, `total-timeout`, and the settings under `stratum.http-client.clients.partner`.
- The verb: the advice cannot see it from the method. Either the annotation names it (`method = POST`), or Stratum's
  request factory reports the verb and whether the request was sent back to the advice. The second needs a probe.
- The self-call trap and public bean methods only: accepted (clients live in their own class).
- Adds `spring-aop` and AspectJ weaving support to the starter (Boot's `spring-boot-starter-aop`), a new dependency.

## Option 3: `restClient(name)` carries the client's retry and breaker itself (no team code, no annotation)
```java
var orders = new PartnerOrdersClient(stratumHttpClients.restClient("partner"));
orders.getOrder(id);   // retried and guarded like the client's interfaces
```
- Stratum wraps the client's request factory, not an interceptor, so bodies are not buffered (C1). Each attempt
  makes a fresh request from the same factory.
- Calls with no body (`GET`, `DELETE`, `HEAD`) retry under B1's rules. A call with a body is sent once, because its
  body can be written only once. The breaker guards every call.
- The team writes nothing: it is the same behaviour as an interface, which is the most Spring-like result.
- It reverses your 13:38Z decision ("restClient never retries") and ADR 0012 decision 3, so it needs a new ADR line.
- Not verified: wrapping the request factory without buffering, and a span per call rather than per attempt. A probe
  comes before the note.

## Your concern: when Spring changes its HTTP client or resilience configuration
- Stratum reads none of Spring Boot's `spring.http.clients.*` keys for its clients (ADR 0011). It uses only long-stable
  public types: `ClientHttpRequestFactory` (since Spring 3.0), `RestClient.Builder`, and the HTTP service registry.
  It uses Resilience4j's core library, not its Spring Boot module. A change to Boot's keys does not reach Stratum's
  clients.
- What can still break on a Spring upgrade is caught by Stratum's own test suite on the upgrade pull request (the
  retry, breaker and streaming integration tests), before any service gets it.
- Option 1 moves that risk from Stratum to every team. Option 3 has the smallest surface: one factory wrapper.

## Recommendation
**Option 3.** It is the one with no explicit calls at all, it behaves exactly like the client's interfaces, and it adds
no dependency. Bodied calls stay sent once, which is the safe rule anyway. Option 2 is the fallback if you want the
retry on a whole method (several calls, or an SDK) rather than per request. Option 1 is not safe for writes and has no
breaker.

A vendor SDK on its own engine (judge F1) is out of every option. That goes in an issue.
