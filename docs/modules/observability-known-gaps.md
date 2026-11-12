# Observability: known gaps and backends

This page belongs to the [observability starter](../../starters/stratum-starter-observability/README.md).
It says which backends Stratum tests and lists every known gap of the module in one place. Each gap
is explained where its feature is described; the links below lead there.

## Backends

The starter sends metrics and traces over OTLP to an OpenTelemetry Collector, which forwards them
to your backends. This is what Stratum's own tests cover, and what it leaves to Spring Boot:

| Backend or transport | Status | What is checked |
| --- | --- | --- |
| OTLP over HTTP, metrics and traces | Tested | Stratum's integration tests send to a stub Collector and read what it receives: metrics, spans, trace headers, baggage, sampling and the removal of exception data |
| OTLP over gRPC (`management.opentelemetry.tracing.export.otlp.transport=grpc`) | Spring Boot only | Stratum tests only that a failed send over gRPC is logged as [described](../../starters/stratum-starter-observability/README.md#running-without-a-collector). Sending under load is not tested ([#230](https://github.com/exepex/stratum/issues/230)) |
| Prometheus, by scraping `/actuator/prometheus` | Spring Boot only | The [example](../../starters/stratum-starter-observability/README.md#examples) was checked once by hand: 69 `_bucket` series per set of tags, and exemplars in the OpenMetrics format. No test keeps it true ([#231](https://github.com/exepex/stratum/issues/231)) |
| Zipkin | Not tested | Stratum neither brings nor tests Spring Boot's Zipkin support. Send OTLP to the Collector and let it export to Zipkin. Whether direct export is supported is open ([#232](https://github.com/exepex/stratum/issues/232)) |

Every other backend, such as Grafana, Datadog or New Relic, is reached through the Collector, so
the service sends the same OTLP whatever the backend.

## Known gaps

| Gap | Where it is explained | Issue |
| --- | --- | --- |
| Database queries make no spans yet; until then, use the pool and repository metrics | [Observing your service's stack](observability-service-stack.md#postgresql) | [#225](https://github.com/exepex/stratum/issues/225) |
| A caller that chooses its own trace IDs can force sampling | [Sampling](observability-traces.md#how-a-service-chooses) | [#162](https://github.com/exepex/stratum/issues/162) |
| Allowed baggage entries go to every host called, third parties included, except a Stratum HTTP client's host with [`trace-headers: false`](http-client-observability.md#trace-headers) | [Baggage](observability-traces.md#baggage) | [#171](https://github.com/exepex/stratum/issues/171) |
| JMS connection pools have no metrics, and TIBCO EMS is not tested | [Messages](observability-messages.md#not-covered-yet) | [#226](https://github.com/exepex/stratum/issues/226) |
| JMS templates and containers made outside beans, and listeners registered after startup, are not observed | [Messages](observability-messages.md#jms-senders-and-listeners-you-set-up) | none: Stratum cannot reach them |
| Two health-probe cases still make spans: a span a health check starts with the tracer or the OpenTelemetry API, and, on Spring MVC with a separate management port, timed work inside a health check | [Health probes](observability-traces.md#health-probes) | none: stated |
| A span made with the OpenTelemetry API keeps its exception data while it waits in the queue | [Errors on spans](observability-traces.md#errors-on-spans) | none: stated |
| A full batch of 512 large spans, and a metrics push with many meters, have not been checked against the Collector's receive limit | [Dropped spans](observability-traces.md#dropped-spans) | [#228](https://github.com/exepex/stratum/issues/228) |
| A Collector that never answers can slow shutdown by up to 30 s | [Running without a Collector](../../starters/stratum-starter-observability/README.md#running-without-a-collector) | [#227](https://github.com/exepex/stratum/issues/227) |
| Each rollout starts new series, because `host` and `service.instance.id` change | [Seeing one pod or all of them](../../starters/stratum-starter-observability/README.md#seeing-one-pod-or-all-of-them) | none: by design |
| The cost of Reactor's `auto` context propagation is not measured | [Threads and reactive code](../../starters/stratum-starter-observability/README.md#threads-and-reactive-code) | [#131](https://github.com/exepex/stratum/issues/131) |
| A pool that Stratum wraps to carry log fields has no pool metrics | [Log fields across threads](../guides/context-across-threads.md#blocking-code) | none: stated, with the way to get them |
| Redis caches count hits and misses only because the cache starter turns statistics on; Spring Boot leaves them off | [Cache](cache.md#metrics) | none: done by the cache starter |
| Active users are counted in the log backend, never in metrics, because each user would make a series | [Counting active users](observability-service-stack.md#counting-active-users) | none: by design |
| A service's own `extra-keys` and `extra-patterns` do not mask the query on an outgoing call's span; the built-in rules do | [URLs on spans and metrics](observability-traces.md#urls-on-spans-and-metrics) | [#254](https://github.com/exepex/stratum/issues/254) |
