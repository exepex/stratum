# Logging: rate limits and sampling

This page belongs to the [logging starter](../../starters/stratum-starter-logging/README.md).

A bug or an outage can make one line of code log thousands of times a second. Each of those lines
costs the service time to build, mask and write, and costs money in the log platform. The logging
starter caps how many lines each logger writes per second, and can keep only a share of DEBUG and
INFO lines. It decides before Logback builds the line, so a dropped line costs almost nothing.
Once per interval it writes one line that counts what it dropped, so drops are never silent.

It works with Logback, Spring Boot's default logging backend; for Log4j2 it is
[planned](../../starters/stratum-starter-logging/README.md#planned). It is on by default: you
write no code and set no property.

## Rate limits

Each logger may write, per second and for each level:

- up to 1000 TRACE, DEBUG or INFO lines (`stratum.logging.volume.max-per-second`);
- up to 5000 WARN or ERROR lines (`stratum.logging.volume.warn-error-max-per-second`), so errors
  have a higher limit of their own.

A logger is the name you pass to `LoggerFactory.getLogger`, usually a class name. Lines over the
limit in that second are dropped; the next second starts a new count. Lines below the logger's
level, as `logging.level.*` sets it, are never counted, and neither are checks such as
`log.isDebugEnabled()`. The limit applies to each running copy of the service on its own: a limit
for the whole fleet belongs in your log platform.

## Loggers that are never limited

The lines of Spring Security's loggers (`org.springframework.security` and every logger below it)
are never limited or sampled, so a failed login is always written. To add your own, such as an
audit logger, list them in `stratum.logging.volume.never-limit-loggers`. A name covers the loggers
below it: `com.acme.audit` covers `com.acme.audit.Payments`, but not `com.acme.auditing`. Your
list replaces the default, so keep `org.springframework.security` in it.

> [!WARNING]
> The volume of these loggers is never capped. During an attack such as credential stuffing,
> Spring Security can log one line per failed login, at whatever rate the attacker sends them.
> Count failures in a metric rather than relying on one log line each.

## Sampling

Sampling keeps one in N lines and drops the rest; it is off by default. Set
`stratum.logging.volume.debug-keep-one-in` for TRACE and DEBUG lines and
`stratum.logging.volume.info-keep-one-in` for INFO lines. WARN and ERROR lines are never sampled.

When a line carries a trace ID, as with the
[observability starter](../../starters/stratum-starter-observability/README.md), the trace ID
decides: every line of a sampled request is kept, and every line of another request is dropped,
in every service that uses the same rate. A line with no trace ID is kept by a random draw from
Java's `SecureRandom`, which all threads share. That draw costs about 0.1 to 0.2 µs per sampled line
on one thread, and up to a few µs when many threads sample at once; lines with a trace ID never
draw, and with sampling off nothing is drawn.

## The summary of dropped lines

Every 10 seconds (`stratum.logging.volume.report-interval`), if lines were dropped over the
limits, one WARN line is written by the logger `com.exepex.stratum.logging.volume.LogVolumeFilter`,
which is never limited itself:

```json
{
  "log": { "level": "WARN", "logger": "com.exepex.stratum.logging.volume.LogVolumeFilter" },
  "message": "Dropped log lines over the rate limit",
  "droppedLines": 41230,
  "droppedKeys": 2,
  "topDropped": "com.acme.orders.OrderService INFO=41000, com.acme.orders.StockClient ERROR=230"
}
```

| Field | Meaning |
| --- | --- |
| `droppedLines` | Lines dropped over the limits since the last summary |
| `droppedKeys` | How many loggers and levels dropped lines; a logger that dropped INFO and ERROR lines counts twice |
| `topDropped` | The five loggers and levels that dropped the most, each with its count |

Lines left out by sampling are not counted: sampling is your choice, not a flood. The summary is
masked like every line and carries no field of the request that happened to trigger it. It is
written when the interval has passed and the service logs its next line, so a service that has
stopped logging writes it later.

## Memory

Stratum counts at most `stratum.logging.volume.max-keys` loggers and levels on their own (1024 by
default). Any more share one count per level, which still holds the limit, and the summary names
them `other loggers`. The table is sized once at startup and never grows: about 105 KB at 1024, and
about 1.7 MB at the highest value, 16384.

## Configure it

Raise the INFO limit, keep one in 100 DEBUG lines and never limit an audit logger, in
`application.properties`:

```properties
stratum.logging.volume.max-per-second=5000
stratum.logging.volume.debug-keep-one-in=100
stratum.logging.volume.never-limit-loggers=org.springframework.security,com.acme.audit
```

or in `application.yml`:

```yaml
stratum:
  logging:
    volume:
      max-per-second: 5000
      debug-keep-one-in: 100
      never-limit-loggers:
        - org.springframework.security
        - com.acme.audit
```

A busy service can also keep one in 10 INFO lines, lower the limit of WARN and ERROR lines, count more
loggers on their own, and get the summary every 30 seconds instead of every 10, in `application.properties`:

```properties
stratum.logging.volume.warn-error-max-per-second=2000
stratum.logging.volume.info-keep-one-in=10
stratum.logging.volume.max-keys=4096
stratum.logging.volume.report-interval=30s
```

or in `application.yml`:

```yaml
stratum:
  logging:
    volume:
      warn-error-max-per-second: 2000
      info-keep-one-in: 10
      max-keys: 4096
      report-interval: 30s
```

To turn the limits off, for example while you look for a missing line, in `application.properties`:

```properties
stratum.logging.volume.enabled=false
```

or in `application.yml`:

```yaml
stratum:
  logging:
    volume:
      enabled: false
```

The [property table](../../starters/stratum-starter-logging/README.md#properties) lists every key with its range.

### Configure in Java

The same settings (with the limits left on), in the logging customizer the
[starter page](../../starters/stratum-starter-logging/README.md#configure-in-java) describes:

```java
public class OrdersLoggingSettings implements LoggingPropertiesCustomizer {

  @Override
  public LoggingProperties customize(LoggingProperties properties) {
    return properties.toBuilder()
        .volume(
            properties.volume().toBuilder()
                .enabled(true)
                .maxPerSecond(5000)
                .warnErrorMaxPerSecond(2000)
                .debugKeepOneIn(100)
                .infoKeepOneIn(10)
                .maxKeys(4096)
                .reportInterval(Duration.ofSeconds(30))
                .neverLimitLoggers(List.of("org.springframework.security", "com.acme.audit"))
                .build())
        .build();
  }
}
```

A Java value wins over the same key in a file.

## Known limits

- Counts run in whole seconds, so up to twice the limit can pass in one second that spans the end
  of one count and the start of the next.
- A line with no message, arguments or exception, such as `log.warn(e.getMessage())` when the
  message is `null`, looks to Logback like a level check, so it is written but not counted.
- Lines written before the application has started, while Spring creates its beans, are not
  limited.
- Logback has one logging context per JVM, so it holds one volume filter: that of the application
  that started last. When several applications run in one JVM, as in tests, closing the later one
  leaves the earlier one unlimited.
- A Logback filter of your own that accepts a line before Stratum's sees it bypasses the count.
- A Logback reconfiguration at run time, such as a Logback file with `scan="true"`, would reset
  Logback and remove the filter. It needs a Logback file of your own, which stops startup with
  `SX-LOGGING-1003` while the module is on.
