# HTTP client: auth and mTLS

How a Stratum client proves who it is to the service it calls: an API key, a bearer token, Basic credentials, or a
client certificate (mutual TLS). Part of the [HTTP client](http-client.md) page, which lists every key.

[← HTTP client](http-client.md) · Design: [ADR 0011](../adr/0011-http-client-starters.md), decision 3

## Choose a kind

Each client sets at most one kind under `auth`. A certificate (`ssl-bundle`) can be added to any of them.
The credential and the certificate go with every call of the client's interfaces and of
`stratumHttpClients.restClient("<name>")`.

| Kind | Use it when | Sent on every call |
| --- | --- | --- |
| `auth.api-key` | A vendor gives you an API key | `X-Api-Key: <key>`, or the header in `auth.api-key-header` |
| `auth.bearer` | A partner gives you a long-lived token | `Authorization: Bearer <token>` |
| `auth.basic` | An older service wants a user name and password | `Authorization: Basic <encoded>` |
| `ssl-bundle` | The service wants a client certificate | the bundle's certificate, during the TLS handshake |

Tokens of your own service (client credentials) and the caller's token (token relay) are planned.

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
password))`. List the class in `META-INF/spring.factories`, as the [HTTP client](http-client.md#configure-in-java)
page shows.
