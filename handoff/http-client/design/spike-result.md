# HTTP client verification spike (2026-11-09)

Gap 21 (A16). This was a throwaway Maven project outside the repository: nothing was committed or pushed. It ran 17
tests, all passing (`spike/run.log`); the code is in `spike/src/`.

Versions resolved from Spring Boot 4.1.2: Spring Framework 7.0.10, Spring Security 7.1.2, Apache HttpClient 5.6.5,
httpcore 5.4.4 and Micrometer 1.17.2. The run used JDK 21, the container's JDK, not 25. Library behaviour should not
differ, but this was not checked on 25.

## Results

| # | Item | Result | Evidence (test, output) |
|---|---|---|---|
| 1 | Each client as its own Spring HTTP service group | **Works** | `ServiceGroupsTest`: `forGroup("orders")` / `forGroup("stock")` in an `AbstractHttpServiceRegistrar`; each proxy hit its own base URL |
| 2 | Own HC5 pool per client | **Works** | Same test: pools `orders` max 7 and `stock` max 3, two different objects, set in `RestClientHttpServiceGroupConfigurer.forEachClient` |
| 3 | Token relay from the security context | **Works, with ~10 lines of Stratum code** | `OAuth2Test`: the interceptor reads `JwtAuthenticationToken` and the downstream got `Bearer caller-access-token`; on another thread the context is empty and the call stopped with our code, with 0 server calls |
| 4 | Client credentials without a user | **Works** | 2 calls led to 1 token fetch (cached); there was no security context at all |
| 5 | Micrometer HC5 pool binder, tagged by client | **Works, gives 4 values** | `MetricsAndProxyTest`: see "Binder meters" below; it accepts our `client` tag |
| 6 | One interceptor, run on every retry attempt | **Works** | `InterceptorRetryTest`: an outer retry interceptor calling `execution.execute()` 3 times re-ran the signer 3 times, with a fresh header each time |
| 7 | HC5 retry off, lifetime, stale check, Keep-Alive, pool wait | **All work** | `HttpClient5Test`, details below |
| 8 | SSL bundle reload | **Works (callback)** | `SslReloadTest`: `SslBundles.addBundleUpdateHandler("bank", ...)` fired 201 ms after the PEM files changed, with the new certificate (`CN=two`) |
| 9 | HC5 honours JVM proxy settings | **Works through Boot's builder** | Boot's builder sent the request to the fake proxy (`http.proxyHost`); plain `HttpClients.custom()` did not |

## Findings that change or confirm the design

1. **Apache HC5 also retries 503 by itself (A1 confirmed, and wider than the review said).** With HC5's default
   retry strategy, one request to a downstream answering 503 reached it **twice**. That held both through Boot's
   builder and with plain `HttpClients.createDefault()`. With `disableAutomaticRetries()` it reached it once.
   With our 3-attempt retry interceptor on top, the server saw 6 calls instead of 3. A1 (Apache retry off) is
   required, not optional.
2. **Boot's per-group keys are ignored once Stratum sets the request factory.**
   `spring.http.serviceclient.orders.read-timeout=1ms` had no effect: a call taking 200 ms succeeded. That fits the
   design, where timeouts are Stratum's keys. The ADR says Boot's `spring.http.serviceclient.*` keys do not apply to
   Stratum clients. The order of Boot's configurer and ours was observed once, not read in Spring's source.
3. **The 401 handling (B3) is half built-in.** Spring Security's `authorizationFailureHandler(service)` dropped the
   cached token on a 401, both with and without `WWW-Authenticate: Bearer error="invalid_token"`, and the next call
   fetched a new token (`svc-2`). Stratum adds only the one retry of the same call.
4. **The token call's timeouts (A3) work, but have a trap.** With a 300 ms read timeout on the token client, a hung
   SSO failed in 302 ms and the downstream got 0 calls. The trap: a custom `RestClient` for
   `RestClientClientCredentialsTokenResponseClient` must again add `FormHttpMessageConverter`,
   `OAuth2AccessTokenResponseHttpMessageConverter` and `OAuth2ErrorResponseErrorHandler`. Without them every token
   call fails with "accessToken cannot be null". The spike hit this, and the implementer brief must say it.
5. **Per-attempt timeout (A8) is possible, but needs a thread-local.** `setHttpContextFactory` plus
   `RequestConfig.setResponseTimeout` gave a 300 ms limit on a 1.5 s call (failed at 331 ms). The context factory
   sees only the method and the URI, so the retry interceptor passes "time left" in a thread-local and clears it
   after each attempt. That is safe on the blocking stack, which keeps one thread per call.
6. **Pool metrics: the binder gives 4 values in 5 gauges, and #237 is confirmed.**
   `httpcomponents.httpclient.pool.total.max`, `...pool.total.connections{state=leased}` (busy),
   `{state=available}` (idle), `...pool.total.pending` (waiting), plus `...pool.route.max.default`. It adds its own
   `httpclient=<name>` tag next to ours. There is no wait-time or refused meter, which is the content of #237.
7. **SSL reload is a callback, not automatic.** Boot gives a new bundle object. Whether an already-built HC5 client
   picks it up by itself was **not tested**. Design: in the handler, Stratum rebuilds that client's connection
   manager, and calls in progress finish on the old one.
8. **Proxy: the egress-proxy issue does not need to move up.** Boot's HC5 builder calls `useSystemProperties()`
   (bytecode of `HttpComponentsHttpClientBuilder`, and the runtime test). So `-Dhttp.proxyHost` works as long as
   Stratum builds through Boot's builder. Only an `http` target was tested; an `https` target through a proxy
   (CONNECT) was **not tested**.
9. **Token exchange exists in Spring Security 7.1.2 (#238).** `TokenExchangeOAuth2AuthorizedClientProvider` and
   `RestClientTokenExchangeTokenResponseClient` are present, with grant
   `urn:ietf:params:oauth:grant-type:token-exchange`. Only the classes were checked, not a full exchange.

## HC5 details (item 7)

| Check | Setting | Result |
|---|---|---|
| Retry on 503 | default / `disableAutomaticRetries()` | 2 server calls / 1 |
| Connection lifetime | `ConnectionConfig.setTimeToLive(500ms)` | 2 quick calls on 1 connection; after 800 ms a new connection (2 in total) |
| Stale check | server closes idle after 300 ms, client calls after 800 ms, Apache retry off | `setValidateAfterInactivity(100ms)`: ok; disabled: `NoHttpResponseException` |
| Server `Keep-Alive: timeout=1` | default keep-alive strategy | after 1.5 s a new connection opened, with no error |
| Pool wait | max 1 connection, `setConnectionRequestTimeout(300ms)` | 2nd call failed in 303 ms with `ConnectionRequestTimeoutException` |
| Boot builder | `withConnectionConfigCustomizer`, `withHttpClientCustomizer` | lifetime, stale check and retry-off all set through Boot's builder |

`ConnectionConfig.DEFAULT` has no lifetime and no stale check (both `null`), so Stratum sets both explicitly.

## Binder meters (item 5)

```
httpcomponents.httpclient.pool.total.max          GAUGE [client=orders, httpclient=orders]
httpcomponents.httpclient.pool.total.connections  GAUGE [client=orders, httpclient=orders, state=leased]
httpcomponents.httpclient.pool.total.connections  GAUGE [client=orders, httpclient=orders, state=available]
httpcomponents.httpclient.pool.total.pending      GAUGE [client=orders, httpclient=orders]
httpcomponents.httpclient.pool.route.max.default  GAUGE [client=orders, httpclient=orders]
```

## Not verified

- JDK 25. The spike ran on JDK 21.
- An already-built HC5 client picking up a reloaded SSL bundle by itself.
- An HTTPS target through a proxy (CONNECT).
- A full token exchange (only the classes were checked).
- Whether HC5's built-in retry also covers 429. The spike tested 503 only, which is moot once retry is off.
- The reactive stack, which is out of scope (MVC starter).
