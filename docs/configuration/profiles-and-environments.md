# Profiles and environments

**Status: Planned.** How to give a service different settings in each environment (your laptop, tests,
staging, production) and how to keep secrets out of configuration files.

[← Documentation home](../README.md)

## The recommended layout

```text
src/main/resources/
├── application.yml          # shared by every environment: safe defaults
├── application-local.yml    # your laptop: readable logs, local URLs
└── application-test.yml     # automated tests (optional)
```

Production and staging values do **not** live in the jar. They come from the deployment (environment
variables, Kubernetes ConfigMaps and Secrets, or a config server). That keeps one tested jar that runs
everywhere, and keeps secrets out of Git.

The same layout works with `.properties` files: `application.properties`,
`application-local.properties`, and so on.

## Profiles

A profile file applies only when its profile is active. Values in it override the shared file.

`application.yml`:

```yaml
spring:
  application:
    name: order-service

stratum:
  logging:
    format: ecs              # JSON for log platforms
  http-client:
    clients:
      inventory:
        base-url: http://inventory-service   # name inside the cluster
```

`application-local.yml`:

```yaml
stratum:
  logging:
    format: text             # easy to read in a terminal
  observability:
    tracing:
      sampling-probability: 1.0   # trace every request while developing
  http-client:
    clients:
      inventory:
        base-url: http://localhost:8081
```

Or, as `application-local.properties`:

```properties
stratum.logging.format=text
stratum.observability.tracing.sampling-probability=1.0
stratum.http-client.clients.inventory.base-url=http://localhost:8081
```

### Switching a profile on

| Where | How |
| --- | --- |
| Command line | `java -jar app.jar --spring.profiles.active=local` |
| Maven | `mvn spring-boot:run -Dspring-boot.run.profiles=local` |
| Environment variable | `SPRING_PROFILES_ACTIVE=local` |
| IntelliJ IDEA | Run configuration → *Active profiles* → `local` |
| Tests | `@ActiveProfiles("test")` on the test class |

Several profiles can be active at once (`--spring.profiles.active=local,debug`). For a key set in
more than one of them, the profile listed **last** wins.

### Profiles Stratum understands

Stratum does not require any profile. It reacts to one name only:

| Profile | What Stratum changes |
| --- | --- |
| `local` | Logs in readable `text` format, tracing samples 100 % of requests, every Actuator endpoint is exposed. Never use it in a shared environment. |

Everything else is up to you. Common names are `local`, `test`, `dev`, `staging` and `prod`.

## Setting values per environment without files

In containers, set properties with environment variables. Kubernetes example:

```yaml
# deployment.yaml (excerpt)
env:
  - name: SPRING_PROFILES_ACTIVE
    value: prod
  - name: STRATUM_HTTPCLIENT_CLIENTS_INVENTORY_BASEURL
    value: http://inventory-service.prod.svc.cluster.local
  - name: STRATUM_OBSERVABILITY_TRACING_SAMPLINGPROBABILITY
    value: "0.05"
```

Spring Boot can also read a whole directory of files as properties, which suits Kubernetes
ConfigMaps and Secrets mounted as volumes:

```yaml
spring:
  config:
    import: "optional:configtree:/etc/config/"
```

A file `/etc/config/stratum.http-client.clients.inventory.base-url` then sets that property.

The rules for converting a property name into an environment variable are in
[Configuration basics](basics.md#environment-variables-and-command-line-arguments).

## Keeping secrets safe

> [!CAUTION]
> Never commit a password, client secret, API key or token to Git, in any file, in any profile.

| Do | Don't |
| --- | --- |
| Reference a secret through a placeholder: `client-secret: ${ORDERS_CLIENT_SECRET}` | Write the secret value in `application.yml` |
| Provide the value from a Kubernetes Secret, a vault or the cloud provider's secret manager | Put secrets in `application-prod.yml` "because it is only prod" |
| Use a separate credential per environment | Reuse one secret everywhere |

A placeholder fails at startup when the value is missing, which is what you want:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          inventory:
            client-secret: ${INVENTORY_CLIENT_SECRET}
```

```properties
spring.security.oauth2.client.registration.inventory.client-secret=${INVENTORY_CLIENT_SECRET}
```

Stratum helps in two more ways:

- `stratum-starter-logging` redacts values of keys that look secret (`password`, `secret`, `token`,
  `authorization`, and more) in log output.
- The `/actuator/env` and `/actuator/configprops` endpoints are **not** exposed by default, and when
  you expose them their values are masked.

Reading secrets straight from a cloud secret manager is covered in [Cloud connectors](../modules/cloud.md).

## Checklist for a new environment

- [ ] `spring.application.name` set (it should be the same in every environment)
- [ ] Profile chosen and activated with `SPRING_PROFILES_ACTIVE`
- [ ] Every downstream `base-url` points at the right environment
- [ ] Issuer URI for token validation points at the right identity provider
- [ ] Secrets come from the platform's secret store, not from files
- [ ] Tracing sampling rate suits the traffic (lower for high volume)
- [ ] `local` profile is **not** active

Next: [Overriding defaults](overriding-defaults.md).
