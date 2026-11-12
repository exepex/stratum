# HTTP client: auth and mTLS

How a Stratum client proves who it is to the service it calls: an API key, a bearer token, Basic credentials, the
service's own token, the caller's token, or a client certificate (mutual TLS). Part of the [HTTP client](http-client.md) page, which lists every key.

[← HTTP client](http-client.md) · Design: [ADR 0011](../adr/0011-http-client-starters.md), decision 3

## Choose a kind

Each client sets at most one kind under `auth`. A certificate (`ssl-bundle`) can be added to any of them.
The credential and the certificate go with every call of the client's interfaces and of its `RestClient` and
`RestTemplate` beans ([clients Stratum did not build](http-client-own-clients.md)), on every attempt.

| Kind | Use it when | Sent on every call |
| --- | --- | --- |
| `auth.api-key` | A vendor gives you an API key | `X-Api-Key: <key>`, or the header in `auth.api-key-header` |
| `auth.bearer` | A partner gives you a long-lived token | `Authorization: Bearer <token>` |
| `auth.basic` | An older service wants a user name and password | `Authorization: Basic <encoded>` |
| `auth.client-credentials` | Your service calls as itself, such as from a scheduled job | `Authorization: Bearer <the service's token>`, see [Client credentials](#client-credentials) |
| `auth.token-relay` | Your service calls on behalf of the user who called it | `Authorization: Bearer <the caller's token>`, see [Token relay](#token-relay) |
| `ssl-bundle` | The service wants a client certificate | the bundle's certificate, during the TLS handshake |

The credential is set on each request Stratum creates, so it goes with every call. Setting it does
not read or hold the request body, so a streamed upload stays streamed.

## Keep secrets out of files

Take every secret from a placeholder, as the examples do. The value then comes from the environment or your secret
store, never from the file:

```properties
stratum.http-client.clients.geo.url=https://api.geo.example
stratum.http-client.clients.geo.auth.api-key=${GEO_API_KEY}
```

```yaml
stratum:
  http-client:
    clients:
      geo:
        url: https://api.geo.example
        auth:
          api-key: ${GEO_API_KEY}
```

Stratum never shows these values: not in a log line, a startup error or the text of the settings.

`auth` and `ssl-bundle` are not allowed under `defaults`, so that one secret or certificate never reaches every client,
an outside vendor included. Setting either there stops startup with `SX-CORE-1003`.

## Examples

An API key in the vendor's own header:

```properties
stratum.http-client.clients.geo.auth.api-key=${GEO_API_KEY}
stratum.http-client.clients.geo.auth.api-key-header=Ocp-Apim-Subscription-Key
```

```yaml
stratum:
  http-client:
    clients:
      geo:
        auth:
          api-key: ${GEO_API_KEY}
          api-key-header: Ocp-Apim-Subscription-Key
```

A fixed bearer token:

```properties
stratum.http-client.clients.partner.auth.bearer=${PARTNER_TOKEN}
```

```yaml
stratum:
  http-client:
    clients:
      partner:
        auth:
          bearer: ${PARTNER_TOKEN}
```

Basic credentials. `basic` is an object with a `username` and a `password`; both are required:

```properties
stratum.http-client.clients.legacy.auth.basic.username=orders
stratum.http-client.clients.legacy.auth.basic.password=${LEGACY_PASSWORD}
```

```yaml
stratum:
  http-client:
    clients:
      legacy:
        auth:
          basic:
            username: orders
            password: ${LEGACY_PASSWORD}
```

## Client credentials

`auth.client-credentials` names a registration of Spring Security's OAuth2 client. Stratum fetches the service's own
token from the identity provider and sends it on every call of the client. Add the starter that brings Spring's OAuth2
client:

```xml
<dependency>
  <groupId>io.github.exepex.stratum</groupId>
  <artifactId>stratum-starter-oauth2-client</artifactId>
</dependency>
```

Then describe the registration with Spring Security's own keys, and name it on the client:

```properties
spring.security.oauth2.client.registration.invoices.authorization-grant-type=client_credentials
spring.security.oauth2.client.registration.invoices.client-id=orders
spring.security.oauth2.client.registration.invoices.client-secret=${INVOICES_CLIENT_SECRET}
spring.security.oauth2.client.registration.invoices.scope=invoices.read
spring.security.oauth2.client.registration.invoices.provider=acme
spring.security.oauth2.client.provider.acme.token-uri=https://idp.acme.example/oauth2/token
stratum.http-client.clients.invoices.url=https://invoices.internal
stratum.http-client.clients.invoices.auth.client-credentials=invoices
```

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          invoices:
            authorization-grant-type: client_credentials
            client-id: orders
            client-secret: ${INVOICES_CLIENT_SECRET}
            scope: invoices.read
            provider: acme
        provider:
          acme:
            token-uri: https://idp.acme.example/oauth2/token
stratum:
  http-client:
    clients:
      invoices:
        url: https://invoices.internal
        auth:
          client-credentials: invoices
```

- **One fetch at a time.** The token is kept until 60 seconds before it expires. When it must be fetched, one call
  fetches it and every other call that needs it waits for that fetch, so a burst of calls sends one token request.
- **A fetch never blocks for long.** It waits at most the time the call has left, and the token request uses the
  client's own timeouts on a small pool of its own, without the client's `ssl-bundle`.
- **A failed fetch fails the call with `SX-HTTPC-0002`** (status 503), when the identity provider times out, fails or
  refuses the client. The call is not sent, not retried, and neither a success nor a failure for the circuit breaker
  ([what counts](http-client-resilience.md#the-circuit-breaker-group)). The error your caller
  sees names nothing internal; a warning in the log, once per client per minute, names the client and the
  registration, never the secret.
- **A 401 gets one more try** on a call of the client's interface. When the other service refuses the token, such as
  after its keys rotate, the token is dropped and the call is sent once more with a new one. A second 401 is thrown
  to your code as a `DownstreamException` with status 401 ([errors](http-client-errors.md)), with one warning per
  client per minute. A call with a streamed body is not sent again, as its body cannot be read twice. A call through
  the client's `RestClient` or `RestTemplate` bean, or `stratumHttpClients.restClient("<name>")`, gets the same second
  try when it has no body; a call with a body gets its 401, as Spring's `HttpClientErrorException.Unauthorized`.
- A missing registration, or one whose `authorization-grant-type` is not `client_credentials`, stops startup with
  `SX-HTTPC-1005`. Without `stratum-starter-oauth2-client`, startup stops with `SX-HTTPC-1006`.

`stratum-starter-oauth2-client` has no keys of its own. It brings Spring Security's OAuth2 client without Spring Boot's
web lock-down, so adding it protects no endpoint and adds no login page, in a web service too. If your service adds
Spring Boot's security starter itself, Spring Boot's own security set-up applies as usual.

## Token relay

`auth.token-relay: true` sends the token of the user who called your service, as Spring Security verified it:

```properties
stratum.http-client.clients.profile.url=https://profile.internal
stratum.http-client.clients.profile.auth.token-relay=true
```

```yaml
stratum:
  http-client:
    clients:
      profile:
        url: https://profile.internal
        auth:
          token-relay: true
```

- Only an access token that Spring Security's OAuth2 resource server verified is sent, such as the JWT of the incoming
  request. The service needs Spring Security's resource server (`spring-boot-starter-oauth2-resource-server`); without
  it, startup stops with `SX-HTTPC-1006`.
- Without such a token the call stops with `SX-HTTPC-0004` (status 500) and nothing is sent, so the circuit breaker
  records neither a success nor a failure. That happens when no one
  is signed in, as in a scheduled job, when the caller signed in another way, such as with a password, and when the
  call runs on another thread than the request, such as in `CompletableFuture.supplyAsync`, since the token is read
  from the request's thread. Use `auth.client-credentials` for calls your service makes as itself.
- A 401 from the other service is thrown as a `DownstreamException` with status 401, without a second try: only the
  caller can get a new token.

## Client certificates (mutual TLS)

Describe the certificate as a Spring Boot SSL bundle, then name the bundle on the client. The bundle's key store holds
the client's certificate and key; its trust store holds the certificates the client trusts for the other service.

```properties
spring.ssl.bundle.pem.bank.keystore.certificate=file:/etc/certs/client.crt
spring.ssl.bundle.pem.bank.keystore.private-key=file:/etc/certs/client.key
spring.ssl.bundle.pem.bank.truststore.certificate=file:/etc/certs/bank-ca.crt
spring.ssl.bundle.pem.bank.reload-on-update=true
stratum.http-client.clients.bank.url=https://api.bank.example
stratum.http-client.clients.bank.ssl-bundle=bank
```

```yaml
spring:
  ssl:
    bundle:
      pem:
        bank:
          keystore:
            certificate: file:/etc/certs/client.crt
            private-key: file:/etc/certs/client.key
          truststore:
            certificate: file:/etc/certs/bank-ca.crt
          reload-on-update: true
stratum:
  http-client:
    clients:
      bank:
        url: https://api.bank.example
        ssl-bundle: bank
```

- The other service's certificate and host name are always checked.
- With `reload-on-update: true`, a renewed certificate file is picked up without a restart: new connections present
  the new certificate, and open ones end within `connection-lifetime` (5 minutes).
- A bundle name that no `spring.ssl.bundle` defines stops startup with Spring Boot's own error.
- A client that uses the shared pool, by its own `shared-pool: true` or one under `defaults`, cannot have a bundle,
  since the shared pool serves several clients. Setting both stops startup with `SX-CORE-1003`; set `shared-pool: false`
  on that client.

## Configure in Java

Every `auth` key and `ssl-bundle` can be set in an `HttpClientPropertiesCustomizer`, which suits a secret read from a
vault. Java wins over the files:

```java
public class GeoClientSettings implements HttpClientPropertiesCustomizer {

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var geo =
        properties.clientSettings("geo").toBuilder()
            .auth(ClientAuth.builder().apiKey(System.getenv("GEO_API_KEY")).build())
            .build();
    var clients = new HashMap<>(properties.clients());
    clients.put("geo", geo);
    return properties.toBuilder().clients(clients).build();
  }
}
```

Basic credentials are a `BasicCredentials` record: `ClientAuth.builder().basic(new BasicCredentials("orders",
password))`. The token kinds are `ClientAuth.builder().clientCredentials("invoices")` and
`ClientAuth.builder().tokenRelay(true)`. List the class in `META-INF/spring.factories`, as the [HTTP client](http-client.md#configure-in-java)
page shows.
