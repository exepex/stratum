# HTTP client: clients Stratum did not build

**Status: Built** for a generated or third-party client that takes a `RestClient` or a `RestTemplate`, and for a
generated interface. A client with its own HTTP engine gets the client's settings only; its retry and circuit breaker
are planned ([#262](https://github.com/exepex/stratum/issues/262)).

[← HTTP client](http-client.md) · [Documentation home](../README.md) ·
Design: [ADR 0014](../adr/0014-http-client-beans-that-retry.md)

Teams also call other services through code they did not write: an OpenAPI generator's client, a partner's SDK jar,
or an older class built on `RestTemplate`. Each configured client gives that code the same set-up a `@StratumClient`
interface gets, with no Stratum class to call and no wrapper to write.

## The two beans of a client

Every client under `stratum.http-client.clients.<name>` is also two ordinary Spring beans:

- a `RestClient`, named `stratum.http-client.<name>.rest-client`;
- a `RestTemplate`, named `stratum.http-client.<name>.rest-template`.

Both have the client's address, timeouts, connection pool, credential or token, retries, circuit breaker and
`total-timeout`, shared with the client's interfaces. You inject them with `@Qualifier("<name>")`, the way you inject
any Spring bean. For new code, use the `RestClient`; the `RestTemplate` is there so that clients generated for it keep
working.

**Through a constructor,** the usual way:

```java
@Service
class PartnerOrders {
  private final RestClient partner;

  PartnerOrders(@Qualifier("partner") RestClient partner) {
    this.partner = partner;
  }

  Order order(String id) {
    return partner.get().uri("/orders/{id}", id).retrieve().body(Order.class);  // retried on a 503
  }
}
```

**A generated client** gets the bean in a `@Bean` method, and never knows about Stratum:

```java
@Bean
PartnerOrdersApi partnerOrdersApi(@Qualifier("partner") RestClient partner) {
  return new PartnerOrdersApi(partner);   // the generated class; one built on RestTemplate takes the RestTemplate
}
```

**Through a setter:**

```java
@Autowired
void setPartner(@Qualifier("partner") RestTemplate partner) {
  this.partner = partner;
}
```

**By name at run time,** from the application's bean factory. Ask the bean factory, not the
`ApplicationContext`: only the bean factory reads the qualifier of a bean registered in code, and with the context
`qualifiedBeanOfType` finds no bean.

```java
RestClient partner = BeanFactoryAnnotationUtils.qualifiedBeanOfType(beanFactory, RestClient.class, "partner");
// or: stratumHttpClients.restClient("partner"), which returns the same RestClient
```

`beanFactory` is the `BeanFactory` Spring injects, or `context.getAutowireCapableBeanFactory()`. When your service
also has its own `RestClient` bean for that name, `qualifiedBeanOfType` finds both and throws
`NoUniqueBeanDefinitionException`: look your own bean up by its bean name, `beanFactory.getBean("myPartnerClient",
RestClient.class)`, and Stratum's through `stratumHttpClients.restClient("partner")`.

**Your own beans stay yours.** Stratum's beans are injected only where a qualifier names them. Your own
`RestClient` or `RestTemplate` bean, injected without a qualifier, is still the one you get, and a list of
`RestClient` beans holds only yours. Your own `@Bean @Qualifier("partner") RestClient` replaces Stratum's for that
name where a qualifier injects it; a lookup by qualifier at run time then finds both, as above. A lookup by type
alone, such as `getBean(RestClient.class)`, finds Stratum's bean only when it is the only `RestClient` there is, so
always use the qualifier.

**A client needs its `url`.** A client without one stops startup with `SX-HTTPC-1004`, naming the key, as its beans
are made at startup.

## Configure it

The beans read the client's own settings, the same keys its interfaces read
([properties](http-client.md#properties)). A client with no interface at all needs only its block:

```yaml
stratum:
  http-client:
    clients:
      partner:
        url: https://api.partner.example
        timeout: 5s                     # longest wait for the answer, and between bytes
        total-timeout: 8s               # the whole call: attempts, the answer's body, waits for a connection and between attempts
        retry:
          max-retries: 3                # extra attempts, 0 to 9; a call with a body is sent once
          backoff-policy: exponential   # exponential or fixed
          delay: 200ms                  # the first wait before a retry
        circuit-breaker:
          enabled: true                 # one breaker for the client, shared with its interfaces
          sliding-window-size: 100      # how many recent calls the breaker judges
        max-connections: 50
        auth:
          bearer: ${PARTNER_TOKEN}
```

```properties
stratum.http-client.clients.partner.url=https://api.partner.example
stratum.http-client.clients.partner.timeout=5s
stratum.http-client.clients.partner.total-timeout=8s
stratum.http-client.clients.partner.retry.max-retries=3
stratum.http-client.clients.partner.retry.backoff-policy=exponential
stratum.http-client.clients.partner.retry.delay=200ms
stratum.http-client.clients.partner.circuit-breaker.enabled=true
stratum.http-client.clients.partner.circuit-breaker.sliding-window-size=100
stratum.http-client.clients.partner.max-connections=50
stratum.http-client.clients.partner.auth.bearer=${PARTNER_TOKEN}
```

`retry.max-retries: 0` sends each call once. Every key of the `retry` and `circuit-breaker` groups, with its default,
is on [retries and the circuit breaker](http-client-resilience.md#the-retry-group), and setting them in Java, through
`retry().toBuilder()` and `circuitBreaker().toBuilder()`, in its
[Configure in Java](http-client-resilience.md#configure-in-java).

## Configure in Java

**Settings.** A client set only in Java gets its two beans too. Your `HttpClientPropertiesCustomizer`, listed in
`META-INF/spring.factories`, sets any key, and Java wins over the files:

```java
public class PartnerClientSettings implements HttpClientPropertiesCustomizer {

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var partner =
        properties.clientSettings("partner").toBuilder()
            .url("https://api.partner.example")   // a client set only in Java needs its url here
            .timeout(Duration.ofSeconds(3))
            .build();
    var clients = new HashMap<>(properties.clients());
    clients.put("partner", partner);
    return properties.toBuilder().clients(clients).build();
  }
}
```

```properties
# META-INF/spring.factories
com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer=com.acme.orders.PartnerClientSettings
```

**What has no key,** such as a header, or retry and breaker rules in code, goes on a `StratumClientCustomizer`
bean ([customize](http-client.md#customize)). It reaches the client's interfaces and both beans:

- `customize` changes the `RestClient` bean's builder, as it changes the interfaces';
- `customizeRestTemplate` changes the `RestTemplate` bean's builder. Spring Boot's `RestTemplateBuilder` returns a
  new builder from each method, so return the builder you made; Stratum hands it to the next customizer, in their
  order;
- `customizeRetry` and `customizeCircuitBreaker` change the rules both beans share with the interfaces.

```java
@Bean
StratumClientCustomizer partnerSetup() {
  return new StratumClientCustomizer() {
    @Override
    public void customize(String client, RestClient.Builder builder) {
      if ("partner".equals(client)) {
        builder.defaultHeader("X-Tenant", "retail");
      }
    }

    @Override
    public RestTemplateBuilder customizeRestTemplate(String client, RestTemplateBuilder builder) {
      return "partner".equals(client) ? builder.defaultHeader("X-Tenant", "retail") : builder;
    }

    @Override
    public void customizeCircuitBreaker(String client, CircuitBreakerConfig.Builder breaker) {
      if ("partner".equals(client)) {
        breaker.slidingWindowSize(20).minimumNumberOfCalls(20);
      }
    }
  };
}
```

## What is retried

A call through either bean runs through the client's retries and circuit breaker, by the rules its interfaces use:

| Call | Retried? |
| --- | --- |
| `GET`, `HEAD`, `DELETE`, `OPTIONS` or `PUT` with no body, answered 429, 502, 503, 504 or with no answer | Yes, up to `retry.max-retries`, honouring `Retry-After` |
| Any call with a body: `POST`, `PATCH`, an upload, and a `PUT` with a body | Never: the body is streamed once, never held in memory |
| An answer of 404, 400 or another 4xx except 429 | No, and the breaker does not count it |
| Any 5xx or 429 | Counted by the client's circuit breaker, which its interfaces share |
| A call while the breaker is open | Refused at once with `SX-HTTPC-0003` (503) |
| A 401 on a client with `auth.client-credentials` | Sent once more with a new token, for a call with no body; a call with a body gets its 401 |

The whole call, its retries, waits and the reading of its answer's body included, ends by `total-timeout`, 8s by
default: each attempt waits for the answer at most `timeout` or the time left, whichever is less, as an interface's
call does. To call a slow service, raise `total-timeout` for that client or under `defaults`, for example to 30s.

An error answer is read inside its attempt, at most 64 KB, and its connection freed before any wait for the next
attempt, as an interface's is. A success answer is handed over unread after the last attempt, and read up to
`response-body-limit`, 32 MB by default: a larger one fails with Spring's `RestClientException`, whose cause's message
starts with `SX-HTTPC-0006`, and is not retried ([errors](http-client-errors.md#the-answer-body-is-read-up-to-32-mb)).
A body still arriving when `total-timeout` runs out is cut off: `body(...)` throws Spring's `RestClientException`
caused by `SocketTimeoutException`, and a stream you read yourself in `exchange(...)` throws the
`SocketTimeoutException`. Neither is retried or counted. `toBodilessEntity()` returns the status and headers. For large
downloads, raise `total-timeout` for that client, or use an interface method that returns `InputStream`.

## What differs from an interface

- **Spring's own exceptions.** A failed call throws `HttpClientErrorException`, `HttpServerErrorException` or
  `ResourceAccessException`, as a `RestClient` or `RestTemplate` you built would, because a generated client's own
  error handling expects them. A 404 is `HttpClientErrorException.NotFound`. `DownstreamException`, `mapError` and the
  empty `Optional` on a 404 are for interfaces only ([errors](http-client-errors.md)). Three Stratum codes
  remain, as for an interface: `SX-HTTPC-0003` for a call refused by an open breaker, `SX-HTTPC-0002` for a token
  that cannot be fetched, and `SX-HTTPC-0004` for a token-relay call with no verified caller.
- **A body is never sent twice.** An interface may resend a `POST` whose connection never opened, and a `PUT`.
- **A body cut off after the answer's head** is neither retried nor counted by the breaker.
- **One span and one `http.client.requests` metric per call,** with the last answer; an interface records one per
  attempt. Stratum has no retry metric yet.
- **Your own interceptor or request initializer,** added in `customize`, `customizeRestTemplate` or a
  `RestTemplateCustomizer` bean, runs once per call, outside the retries, and an interceptor makes Spring hold the
  request body in memory. A customizer that replaces the request factory also removes the retries and the breaker.
- **Without Spring Boot's `RestTemplateBuilder` bean,** as in a WebFlux service, the `RestTemplate` is built from a
  plain `RestTemplateBuilder`: it has Spring's default message converters and records no span.
- **A mocked client** (`mock: true`) answers its beans' calls with `501`, naming the client; only its interfaces'
  methods are mocked ([mocked clients](http-client-mocks.md)). These answers count against its breaker.

## A client with its own engine

A vendor SDK with its own HTTP engine, not Spring's, reads the client's settings, its own merged with `defaults`,
from `stratumHttpClients.settings("partner")`, for example `settings.url()` and `settings.timeout()`. Retry and
circuit breaker for such an engine are planned ([#262](https://github.com/exepex/stratum/issues/262)).

## A generated interface

A generated interface with Spring's `@GetExchange`, but no `@StratumClient`, joins a client through its `interfaces`
setting instead, and then gets everything a `@StratumClient` interface gets
([clients Stratum did not build](http-client.md#clients-stratum-did-not-build)).
