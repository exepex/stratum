# Troubleshooting

**Status: Planned.** How to find out what Stratum configured in your service, and fixes for the most
common problems. Module-specific problems are listed at the end of each module page.

[← Documentation home](../README.md)

## See what Stratum configured

### 1. The startup line

Every Stratum service logs its active modules at startup:

```text
Stratum 0.1.0 active modules: web, logging, observability, http-client (disabled: cache)
```

### 2. The conditions report

Start with `--debug` to print Spring Boot's **conditions evaluation report**. It lists every
auto-configuration and whether it was applied, and why:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--debug
# or
java -jar order-service.jar --debug
```

Look for `Stratum...AutoConfiguration` entries:

```text
Positive matches:
   StratumHttpClientAutoConfiguration matched:
      - @ConditionalOnStratumModule http-client stratum.http-client.enabled is true

Negative matches:
   StratumCacheAutoConfiguration:
      Did not match:
         - @ConditionalOnStratumModule cache stratum.cache.enabled is not true, so it is off
```

### 3. Actuator, in a safe environment

On your laptop (the `local` profile exposes these endpoints) you can inspect the running service:

| Endpoint | Shows |
| --- | --- |
| `/actuator/conditions` | The same conditions report as `--debug` |
| `/actuator/configprops` | Every bound `@ConfigurationProperties`, including `stratum.*`, with values (secrets masked) |
| `/actuator/env` | Every property and which source it came from |
| `/actuator/beans` | Every bean, its type and where it was defined |

> [!CAUTION]
> Never expose these endpoints in a shared environment.

## Common problems

### Startup

| Message or symptom | Cause | Fix |
| --- | --- | --- |
| `Binding to target ... failed`, `Property: stratum...` | A `stratum.*` value is missing or invalid | The message names the property and the reason. See [Validation](../configuration/basics.md#validation-wrong-values-fail-fast). |
| `Could not resolve placeholder 'X'` | A `${X}` placeholder has no value | Set the environment variable `X`, or check its spelling. |
| `NoSuchMethodError` or `ClassNotFoundException` after an upgrade | A dependency has a hard-coded `<version>` that no longer matches | Remove the `<version>` so the BOM manages it. Check with `mvn dependency:tree`. |
| `The bean 'X' could not be registered. A bean with that name has already been defined` | You defined a bean with the same **name** but a different type | Rename your bean method. |
| A property seems to have no effect | Typo in the key, wrong indentation in YAML, or overridden by a higher-priority source | Check the key in your IDE (unknown keys are highlighted), then check `/actuator/env` locally. |

### YAML mistakes

| Mistake | Example | Fix |
| --- | --- | --- |
| Tabs instead of spaces | `→read-timeout: 5s` | YAML allows spaces only. |
| Wrong indentation | `read-timeout` at the same level as `clients` | Indent each level by 2 spaces. |
| Missing space after `:` | `read-timeout:5s` | Write `read-timeout: 5s`. |
| Unquoted special value | `version: 2.10` read as the number 2.1 | Quote it: `version: "2.10"`. |
| `yes`, `no`, `on`, `off` read as booleans | `mode: on` | Quote it: `mode: "on"`. |

### At runtime

| Symptom | Where to look |
| --- | --- |
| Requests return `401` or `403` | [Security → Troubleshooting](../modules/security.md#troubleshooting) |
| Calls to another service time out | [HTTP client → Troubleshooting](../modules/http-client.md#troubleshooting) |
| `CallNotPermittedException` | [Resilience → Troubleshooting](../modules/resilience.md#troubleshooting) |
| No trace IDs in logs, or traces broken between services | [Observability → Troubleshooting](../../starters/stratum-starter-observability/README.md#troubleshooting) |
| Connection pool exhausted | [Data → Troubleshooting](../modules/data-jpa.md#troubleshooting) |
| Messages in a dead-letter topic | [Kafka → Troubleshooting](../modules/kafka.md#troubleshooting) |

### Change a log level without a redeploy

Log levels are set with Spring Boot's `logging.level.*` properties. To change one without a
redeploy, use Spring Boot Actuator's `loggers` endpoint. It is not exposed over HTTP by default;
expose it only on the management port, behind authentication:

```properties
management.server.port=8081
management.endpoints.web.exposure.include=health,loggers
```

```yaml
management:
  server:
    port: 8081
  endpoints:
    web:
      exposure:
        include: health,loggers
```

```bash
curl -X POST -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}' \
  http://localhost:8081/actuator/loggers/com.acme.orders
```

A Stratum endpoint that switches DEBUG on for a limited time, and back off by itself, is
[planned](../../starters/stratum-starter-logging/README.md#planned).

## Getting help

When you ask for help, include:

1. The Stratum version (from the startup line) and the starters you use.
2. The full error message and stack trace, with secrets removed.
3. The relevant part of your configuration, with secrets removed.
4. What you expected and what happened instead.
