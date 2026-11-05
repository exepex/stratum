# Observability: meter caps

This page belongs to the [observability starter](../../starters/stratum-starter-observability/README.md).

Each distinct set of tag values makes a new meter, and each meter is a time series the metrics
backend stores and charges for. A tag that holds many values, such as one host per tenant, can make
thousands of meters. With the module on, Stratum caps the number of meters:

- **Total meters.** Each metrics registry holds at most `stratum.observability.metrics.max-meters`
  meters, 10,000 by default. Past the cap, new meters are dropped; the meters already held keep
  counting. A meter the service or a library removes, as Kafka's client metrics do on every
  rebalance, frees its slot.
- **HTTP client hosts.** The HTTP client meters (`http.client.requests`, or the name in Spring Boot's
  `management.observations.http.client.requests.name`) keep at most
  `stratum.observability.metrics.max-client-names` hosts in their `client.name` tag, 100 by default.
  A call to a new host past the cap is not timed. A host stays counted after its meters are removed.

The first time each cap drops a meter, Stratum logs one warning,
[`SX-OBSERVABILITY-1003`](../guides/error-codes.md#stratum-starter-observability), naming the meter
and the key to raise, never a tag value. It is not logged again for that cap.

When the warning appears, first look for the tag that holds unbounded values: an ID, a raw path, or a
host per customer. Raise a cap only after checking that the backend can store that many series.

```properties
stratum.observability.metrics.max-meters=20000
stratum.observability.metrics.max-client-names=200
```

```yaml
stratum:
  observability:
    metrics:
      max-meters: 20000
      max-client-names: 200
```

In Java, an `ObservabilityPropertiesCustomizer` listed in `META-INF/spring.factories` wins over both
files:

```java
public class GatewayObservabilitySettings implements ObservabilityPropertiesCustomizer {

  @Override
  public ObservabilityProperties customize(ObservabilityProperties settings) {
    return settings.toBuilder()
        .metrics(settings.metrics().toBuilder().maxClientNames(200).build())
        .build();
  }
}
```

To change the rule itself, declare your own `MeterCountGuard` bean, and Stratum's backs off.

## The `uri` tag cap

Spring Boot caps the `uri` tag of HTTP metrics on its own: `management.metrics.web.client.max-uri-tags`
and `management.metrics.web.server.max-uri-tags`, 100 each by default. Stratum leaves both as Spring
Boot sets them. A client that builds its URLs by joining strings, such as
`restClient.get().uri("/orders/" + id)`, makes one `uri` value per order and reaches the client cap
after 100 orders; from then on a call is timed only if its `uri` value is one of those 100, so new
paths go unseen. Spring Boot then logs once: `Reached the maximum number of 'uri' tags for 'http.client.requests'. Are you
using 'uriVariables'?` Pass the ID as a URI variable instead, `uri("/orders/{id}", id)`, so every
order shares one `uri` value.

## Limits

- With one OTLP export the caps hold for the whole service. With Prometheus as well, both
  registries share one count, so they hold the same meters.
- A `MeterFilter` of the service that answers `ACCEPT` before Stratum's runs skips the caps, because
  Micrometer stops at the first filter that accepts or denies.
