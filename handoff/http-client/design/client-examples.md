# Stratum HTTP clients: one example per client type

Date: 2026-11-07. Illustrates the agreed design (survey.md section 11). Names of annotations, keys and Java methods
are proposals, not built yet. Secrets always come from placeholders (`${...}`), never written in files.

Every example has four parts: YAML, the same in Java, the interface, and one line of use.
The Java form is a customizer class listed in `META-INF/spring.factories` (ST-ENG-34); Java wins over YAML.

## 0. Defaults for every client (set once, optional)

```yaml
stratum:
  http-client:
    defaults:
      connect-timeout: 2s
      read-timeout: 5s
      retry: { enabled: true, max-attempts: 3 }        # safe methods only (GET, PUT, DELETE, HEAD)
      circuit-breaker: { enabled: true }
```

```java
public class CompanyHttpClientDefaults implements HttpClientPropertiesCustomizer {
  @Override
  public void customize(HttpClientProperties settings) {
    settings.defaults().readTimeout(Duration.ofSeconds(5)).retry(retry -> retry.maxAttempts(3));
  }
}
```

## 1. Plain client (no auth)

```yaml
stratum.http-client.clients.catalog:
  base-url: https://catalog.internal
```

```java
settings.client("catalog").baseUrl("https://catalog.internal");
```

```java
@StratumClient("catalog")
public interface CatalogClient {
  @Get("/products/{sku}")  Optional<Product> findProduct(String sku);
  @Get("/products")        List<Product> searchProducts(String category, int page);   // ?category=..&page=..
}
```

```java
Optional<Product> product = catalogClient.findProduct("SKU-42");
```

## 2. Basic auth client

```yaml
stratum.http-client.clients.legacy-billing:
  base-url: https://billing.internal
  read-timeout: 20s                                  # overrides the 5s default
  auth:
    type: basic
    username: ${BILLING_USERNAME}
    password: ${BILLING_PASSWORD}
```

```java
settings.client("legacy-billing")
    .baseUrl("https://billing.internal")
    .readTimeout(Duration.ofSeconds(20))
    .auth(Auth.basic(env.getProperty("BILLING_USERNAME"), env.getProperty("BILLING_PASSWORD")));
```

```java
@StratumClient("legacy-billing")
public interface BillingClient {
  @Post("/invoices")       Invoice createInvoice(NewInvoice invoice);
}
```

```java
Invoice invoice = billingClient.createInvoice(newInvoice);
```

## 3. Bearer token client (fixed token from a secret)

```yaml
stratum.http-client.clients.reporting:
  base-url: https://reports.partner.com
  auth:
    type: bearer
    token: ${REPORTING_TOKEN}
```

```java
settings.client("reporting").baseUrl("https://reports.partner.com").auth(Auth.bearer(reportingToken));
```

```java
@StratumClient("reporting")
public interface ReportingClient {
  @Get("/reports/{id}")    Report getReport(String id);
}
```

```java
Report report = reportingClient.getReport("2026-Q3");
```

## 4. API key client

```yaml
stratum.http-client.clients.maps:
  base-url: https://maps.vendor.com
  auth:
    type: api-key
    header-name: X-Api-Key
    key: ${MAPS_API_KEY}
```

```java
settings.client("maps").baseUrl("https://maps.vendor.com").auth(Auth.apiKey("X-Api-Key", mapsApiKey));
```

```java
@StratumClient("maps")
public interface MapsClient {
  @Get("/geocode")         Location geocode(String address);                           // ?address=..
}
```

```java
Location location = mapsClient.geocode("1 Main St");
```

## 5. OAuth2 client credentials client (Stratum gets, caches and refreshes the token)

```yaml
spring.security.oauth2.client:                     # Spring Security's own keys for the token issuer
  registration.orders:
    authorization-grant-type: client_credentials
    client-id: ${ORDERS_CLIENT_ID}
    client-secret: ${ORDERS_CLIENT_SECRET}
    scope: orders.write
  provider.orders.token-uri: https://login.company.com/oauth2/token

stratum.http-client.clients.orders:
  base-url: https://orders.internal
  auth:
    type: client-credentials
    registration: orders                           # name of the registration above
```

```java
settings.client("orders").baseUrl("https://orders.internal").auth(Auth.clientCredentials("orders"));
```

```java
@StratumClient("orders")
public interface OrderClient {
  @Post("/orders")              Order createOrder(NewOrder order);
  @Get("/orders/{orderId}")     Optional<Order> findOrder(String orderId);
  @Patch("/orders/{orderId}")   Order changeDeliveryAddress(String orderId, Address address);
  @Delete("/orders/{orderId}")  void cancelOrder(String orderId);
}
```

```java
Order order = orderClient.createOrder(newOrder);
```

## 6. Token relay client (passes the caller's own token on)

```yaml
stratum.http-client.clients.profile:
  base-url: https://profile.internal
  auth:
    type: token-relay                              # the incoming request's validated token is sent on
```

```java
settings.client("profile").baseUrl("https://profile.internal").auth(Auth.tokenRelay());
```

```java
@StratumClient("profile")
public interface ProfileClient {
  @Get("/me")              Profile getCallerProfile();
}
```

```java
Profile profile = profileClient.getCallerProfile();
```

## 7. mTLS client (client certificate from a Spring Boot SSL bundle)

```yaml
spring.ssl.bundle.pem.bank-gateway:                # Spring Boot's own SSL bundle keys
  keystore:
    certificate: ${BANK_CERT_PATH}
    private-key: ${BANK_KEY_PATH}
  truststore.certificate: ${BANK_CA_PATH}

stratum.http-client.clients.bank-gateway:
  base-url: https://gateway.bank.com
  auth:
    type: mtls
    ssl-bundle: bank-gateway
```

```java
settings.client("bank-gateway").baseUrl("https://gateway.bank.com").auth(Auth.mtls("bank-gateway"));
```

```java
@StratumClient("bank-gateway")
public interface BankGatewayClient {
  @Post("/payments")       PaymentResult submitPayment(PaymentRequest payment);
}
```

```java
PaymentResult result = bankGatewayClient.submitPayment(payment);
```

## 8. Load-balanced client (several addresses, client picks one)

```yaml
stratum.http-client.clients.inventory:
  base-url: lb://inventory                         # lb:// = pick from the addresses below
  addresses:
    - https://inventory-1.dc1.internal
    - https://inventory-2.dc1.internal
    - https://inventory-3.dc2.internal
  auth:
    type: client-credentials
    registration: inventory
```

```java
settings.client("inventory")
    .baseUrl("lb://inventory")
    .addresses(List.of("https://inventory-1.dc1.internal", "https://inventory-2.dc1.internal"))
    .auth(Auth.clientCredentials("inventory"));
```

```java
@StratumClient("inventory")
public interface InventoryClient {
  @Get("/stock/{sku}")     Stock getStock(String sku);
  @Put("/stock/{sku}")     Stock setStock(String sku, StockLevel level);
}
```

```java
Stock stock = inventoryClient.getStock("SKU-42");
```

## 9. Reactive client (needs `stratum-starter-reactive-http-client`)

Same YAML and Java as any client above; only the return types change.

```java
@StratumClient("orders")
public interface ReactiveOrderClient {
  @Get("/orders/{orderId}")  Mono<Order> findOrder(String orderId);
  @Get("/orders")            Flux<Order> streamOrders(String status);
}
```

```java
Mono<Order> order = reactiveOrderClient.findOrder("A-1001");
```

## 10. Overriding resilience for one client

```yaml
stratum.http-client.clients.payments:
  base-url: https://payments.internal
  retry: { enabled: false }                        # payments are never retried
  circuit-breaker: { failure-rate-threshold: 25% }
  auth: { type: client-credentials, registration: payments }
```

```java
settings.client("payments").retry(retry -> retry.enabled(false)).circuitBreaker(cb -> cb.failureRateThreshold(25));
```

## 11. Ad-hoc call without an interface (same settings as the named client)

```java
String health = stratumHttpClients.restClient("orders").get().uri("/health").retrieve().body(String.class);
```
