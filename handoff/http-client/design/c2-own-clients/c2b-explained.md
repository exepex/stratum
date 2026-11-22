# How a team uses a Stratum client's `RestClient` and `RestTemplate` (C2b, explained)

This page shows, with examples, what a team writes under the design in `c2b-design-note.md`. Nothing here is built
yet. The code comes after this design is agreed.

## The idea in one line

Every client you configure under `stratum.http-client.clients.<name>` gives you two ordinary Spring beans:

- a `RestClient`;
- a `RestTemplate`.

You inject them with `@Qualifier("<name>")`, the way you inject any Spring bean. Both have the client's whole Stratum
set-up: address, timeouts, connection pool, credential, retry, circuit breaker and tracing. There is no Stratum
method to call and no wrapper to write.

The `@StratumClient` interfaces do not change.

## 1. Configure the client: yml, properties or Java

**application.yml**
```yaml
stratum:
  http-client:
    clients:
      partner:
        url: https://api.partner.example
        timeout: 5s            # longest wait for the first byte, and between bytes
        total-timeout: 8s      # whole call, retries and waits included
        retries: 3             # GET, HEAD, DELETE, OPTIONS only; a call with a body is sent once
        retry-delay: 200ms
        circuit-breaker: true
        max-connections: 50
        auth:
          bearer: ${PARTNER_TOKEN}
```

**application.properties** (the same settings)
```properties
stratum.http-client.clients.partner.url=https://api.partner.example
stratum.http-client.clients.partner.timeout=5s
stratum.http-client.clients.partner.retries=3
stratum.http-client.clients.partner.auth.bearer=${PARTNER_TOKEN}
```

**Java: settings.** Java wins over the files. It is registered in `META-INF/spring.factories`, as for every Stratum
module.
```java
public class PartnerClientSettings implements HttpClientPropertiesCustomizer {

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var partner =
        properties.clientSettings("partner").toBuilder()
            .timeout(Duration.ofSeconds(3))
            .retries(2)
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

**Java: what has no setting**, such as a header, the retry rules or the breaker. A `StratumClientCustomizer` bean
reaches the client's interfaces and its `RestClient`. The new `customizeRestTemplate` method reaches its
`RestTemplate`.
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
    public void customizeRestTemplate(String client, RestTemplateBuilder builder) {   // new in C2b
      if ("partner".equals(client)) {
        builder.defaultHeader("X-Tenant", "retail");
      }
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

## 2. Inject the client: constructor, setter or context lookup

**Constructor**, the usual way:
```java
@Service
class PartnerOrders {
  private final RestClient partner;

  PartnerOrders(@Qualifier("partner") RestClient partner) {
    this.partner = partner;
  }

  Order order(String id) {
    return partner.get().uri("/orders/{id}", id).retrieve().body(Order.class);  // retried on 503, guarded
  }
}
```

**Setter:**
```java
@Service
class PartnerReports {
  private RestTemplate partner;

  @Autowired
  void setPartner(@Qualifier("partner") RestTemplate partner) {
    this.partner = partner;
  }
}
```

**Context lookup**, for code that has only the name at run time:
```java
RestClient partner = BeanFactoryAnnotationUtils.qualifiedBeanOfType(context, RestClient.class, "partner");
// or: stratumHttpClients.restClient("partner"), which returns the same object
```

Your own `RestClient` or `RestTemplate` bean, injected without a qualifier, still gets your bean, never Stratum's.
Stratum's beans are registered only for qualified injection.

## 3. A generated or third-party client built on `RestClient`

An OpenAPI generator, or a partner's SDK jar, gives you a class that takes a `RestClient`. You hand it Stratum's bean:
```java
@Configuration
class PartnerClients {

  @Bean
  PartnerOrdersApi partnerOrdersApi(@Qualifier("partner") RestClient partner) {
    return new PartnerOrdersApi(partner);    // the generated class; it never knows about Stratum
  }
}
```
Every call the generated class makes now has the partner client's timeouts, pool, token, retry, breaker and tracing.
Your services inject `PartnerOrdersApi` as usual.

## 4. A generated client built on `RestTemplate`

Older generators produce a client that takes a `RestTemplate`. It works the same way:
```java
@Bean
PartnerPaymentsApi partnerPaymentsApi(@Qualifier("partner") RestTemplate partner) {
  return new PartnerPaymentsApi(partner);
}
```
For new code we recommend `RestClient`. The `RestTemplate` bean is there so that existing generated clients keep
working.

## 5. A wrapper of your own around a generated client

When you want your own small API in front of a generated client, for example to map its types to yours, write a
normal Spring bean around it. Stratum's retry and breaker already sit underneath, so the wrapper holds only your
mapping:
```java
@Component
class PartnerOrderGateway {
  private final PartnerOrdersApi api;     // the generated client from example 3

  PartnerOrderGateway(PartnerOrdersApi api) {
    this.api = api;
  }

  /** Our own view of a partner order. */
  Optional<OrderView> find(String id) {
    try {
      return Optional.of(OrderView.from(api.getOrder(id)));   // retried and guarded by Stratum
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
  }
}
```
To call two partner endpoints as one unit, wrap them the same way. Each call inside is retried on its own.

## 6. What retry does, exactly

| Call | Retried? | Why |
|---|---|---|
| `GET`, `HEAD`, `DELETE`, `OPTIONS` with no body, answered 429, 502, 503, 504 or no answer | yes, up to `retries`, with back-off and `Retry-After` | safe to repeat (ST-ENG-51) |
| Any call with a body (`POST`, `PUT`, `PATCH`, an upload) | never resent | the body is streamed once and never held in memory, so a payment is never sent twice |
| An answer of 400, 404 or another 4xx except 429 | no, and not counted by the breaker | the service is up |
| Any 5xx or 429 | counted by the circuit breaker | one breaker per partner, shared with the client's interfaces |
| Breaker open | refused at once with `SX-HTTPC-0003` (503) | |
| 401, once lane A's token renewal is built | sent again once with a new token, bodiless calls only | the token is set on each attempt |

The whole call, retries and waits included, ends by `total-timeout`.

## 7. What stays different from an interface

- Errors stay Spring's own exceptions (`HttpClientErrorException`, `ResourceAccessException`), because a generated
  client's own error handling expects them. Only an open breaker becomes `SX-HTTPC-0003`.
- A retried call is one span and one `http.client.requests` metric, with the last answer. An interface's call gives
  one per attempt. Stratum's retry metrics show the count.
- A call with a body is never resent. An interface may resend a `POST` whose connection never opened.

## 8. What a client with its own engine gets

A vendor SDK with its own HTTP engine, not Spring's, reads the client's settings
(`stratumHttpClients.settings("partner")`). Retry and breaker for such an engine are a separate GitHub issue.
