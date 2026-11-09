# OAuth2 client: `stratum-starter-oauth2-client`

Brings Spring Security's OAuth2 client, so that an [HTTP client](http-client.md) can send your service's own token
(`auth.client-credentials`). It adds no keys of its own: the registrations use Spring Security's standard keys under
`spring.security.oauth2.client`.

[← Documentation home](../README.md) · How the token is sent: [HTTP client: auth](http-client-auth.md#client-credentials)

## Add it

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-starter-oauth2-client</artifactId>
</dependency>
```

## What it brings

- Spring Boot's OAuth2 client set-up: the registrations under `spring.security.oauth2.client.registration`, their
  providers, and the store of fetched tokens.
- Not Spring Boot's web lock-down. Adding the starter protects no endpoint, adds no login page and creates no user,
  in a web service too: Spring Boot's OAuth2 login chain is left out while Spring Boot's own security set-up is
  absent. Protecting your own endpoints is the job of the security starter; if your service adds Spring Boot's
  security starter itself, Spring Boot's security set-up applies as usual.

The HTTP client starter does the rest: it fetches each registration's token once at a time, keeps it until shortly
before it expires, and sends it on the client's calls. [HTTP client: auth](http-client-auth.md#client-credentials)
shows a full example and what happens when the identity provider fails.

## Switch it off

Remove the dependency. A client whose `auth.client-credentials` still names a registration then stops startup with
`SX-HTTPC-1006`, so the gap is visible at once.

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| Calls fail with `SX-HTTPC-0002` | The identity provider refused the client or did not answer. Check the `client-id`, the `client-secret` placeholder and the provider's `token-uri`. |
| Startup stops with `SX-HTTPC-1005` | The name in `auth.client-credentials` matches no registration, or the registration's `authorization-grant-type` is not `client_credentials`. |
| `401` from the other service after one more try | The token's scope or audience does not match what the other service expects. Check the registration's `scope`. |
