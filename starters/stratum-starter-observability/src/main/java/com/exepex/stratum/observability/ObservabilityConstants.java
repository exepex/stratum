package com.exepex.stratum.observability;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.experimental.UtilityClass;

/** The property keys, tag names and span attributes the observability module uses (ST-ENG-45). */
@StratumInternal
@UtilityClass
public class ObservabilityConstants {

  /** The module's name, as in {@code stratum.observability.enabled}. */
  public static final String MODULE = "observability";

  /** The prefix of the module's own settings. */
  public static final String PREFIX = StratumModuleConstants.PREFIX + "." + MODULE;

  /** The switch that turns the module off when set to {@code false}; unset, the module is on. */
  public static final String ENABLED = StratumModuleConstants.enabledProperty(MODULE);

  /** The service's name, which Spring Boot also sends as the {@code service.name} resource. */
  public static final String APPLICATION_NAME = "spring.application.name";

  /** The environment variable that holds the host's name; in Kubernetes, the pod's name. */
  public static final String HOSTNAME = "HOSTNAME";

  /** Spring Boot's list of actuator endpoints reachable over HTTP. */
  public static final String WEB_EXPOSURE = "management.endpoints.web.exposure.include";

  /**
   * The actuator endpoints Stratum exposes over HTTP by default; a service adds others only behind
   * authentication.
   */
  public static final String EXPOSED_OVER_HTTP = "health,info";

  /** The prefix of Spring Boot's tags added to every metric. */
  public static final String METRIC_TAGS = "management.metrics.tags.";

  /** The metric tag that holds the service's name. */
  public static final String APPLICATION_TAG = "application";

  /** The metric tag that holds {@code stratum.environment}. */
  public static final String ENVIRONMENT_TAG = "environment";

  /** The metric tag that holds the host's or pod's name, so one instance can be viewed alone. */
  public static final String HOST_TAG = "host";

  /** Spring Boot's OpenTelemetry resource attribute for the environment, sent with every span. */
  public static final String ENVIRONMENT_RESOURCE_ATTRIBUTE =
      StratumModuleConstants.resourceAttribute("deployment.environment.name");

  /**
   * Spring Boot's OpenTelemetry resource attribute for the instance ID, sent with every span and
   * metric.
   */
  public static final String INSTANCE_ID_RESOURCE_ATTRIBUTE =
      StratumModuleConstants.resourceAttribute(StratumModuleConstants.SERVICE_INSTANCE_ID);

  /** Spring Boot's switch that stores every timer and distribution summary as a histogram. */
  public static final String HISTOGRAM_FOR_EVERY_TIMING =
      "management.metrics.distribution.percentiles-histogram.all";

  /** Spring Boot's kind of histogram sent over OTLP. */
  public static final String HISTOGRAM_FLAVOR = "management.otlp.metrics.export.histogram-flavor";

  /**
   * The histogram whose buckets grow by powers of two, so any percentile is accurate for one pod
   * and for all pods together, with no bucket limits to choose.
   */
  public static final String EXPONENTIAL_HISTOGRAM = "base2-exponential-bucket-histogram";

  /** Spring Boot's choice of OpenTelemetry sampler, which decides which traces are kept. */
  public static final String TRACING_SAMPLER = "management.opentelemetry.tracing.sampler";

  /**
   * The sampler that keeps a trace only by its trace ID, so a caller's sampled flag cannot make
   * this service keep more than the configured probability of traces.
   */
  public static final String TRACE_ID_RATIO_SAMPLER = "trace-id-ratio";

  /**
   * Spring Boot's span limits, set below its defaults so that each span waiting to be sent has a
   * size bound: attribute values cut at 1,024 characters, at most 64 attributes and 16 events.
   */
  public static final Map<String, Integer> SPAN_LIMITS =
      Map.of(
          "management.opentelemetry.tracing.limits.max-attribute-value-length", 1024,
          "management.opentelemetry.tracing.limits.max-attributes", 64,
          "management.opentelemetry.tracing.limits.max-events", 16);

  /**
   * Spring Boot's switches that trace and time Kafka and RabbitMQ messages, which it leaves off.
   * JMS has none: Spring Boot observes its own JMS beans, and Stratum the ones a service declares.
   */
  public static final List<String> MESSAGING_OBSERVATION_SWITCHES =
      List.of(
          "spring.kafka.template.observation-enabled",
          "spring.kafka.listener.observation-enabled",
          "spring.rabbitmq.template.observation-enabled",
          "spring.rabbitmq.listener.simple.observation-enabled",
          "spring.rabbitmq.listener.direct.observation-enabled",
          "spring.rabbitmq.listener.stream.observation-enabled");

  /**
   * The tag in which Spring puts a RabbitMQ message's routing key on its send timer. A routing key
   * can hold an ID, so the tag guard removes it.
   */
  public static final String RABBIT_ROUTING_KEY_TAG = "messaging.rabbitmq.destination.routing_key";

  /** Spring Boot's switch for sending metrics over OTLP. */
  public static final String METRICS_EXPORT_ENABLED = "management.otlp.metrics.export.enabled";

  /** Spring Boot's switch for every metrics export that has no switch of its own set. */
  public static final String METRICS_DEFAULT_EXPORT_ENABLED =
      "management.defaults.metrics.export.enabled";

  /** Spring Boot's OTLP address for metrics. */
  public static final String METRICS_EXPORT_URL = "management.otlp.metrics.export.url";

  /** Spring Boot's switch for sending spans to any tracing backend that has no switch set. */
  public static final String TRACING_EXPORT_ENABLED = "management.tracing.export.enabled";

  /** Spring Boot's switch for sending spans over OTLP. */
  public static final String TRACING_OTLP_EXPORT_ENABLED = "management.tracing.export.otlp.enabled";

  /**
   * Spring Boot's switch for the whole OpenTelemetry SDK, also set by {@code OTEL_SDK_DISABLED}.
   * When it is off, spans go nowhere, so no span address is needed.
   */
  public static final String OPEN_TELEMETRY_ENABLED = "management.opentelemetry.enabled";

  /** Spring Boot's OTLP address for spans. */
  public static final String TRACING_EXPORT_ENDPOINT =
      "management.opentelemetry.tracing.export.otlp.endpoint";

  /** Spring Boot's baggage names that are also sent in headers of their own; always kept. */
  public static final String BAGGAGE_REMOTE_FIELDS = "management.tracing.baggage.remote-fields";

  /**
   * The span attribute in which Spring puts the full URL of an outgoing HTTP call, or the path of a
   * request the service receives.
   */
  public static final String HTTP_URL = "http.url";

  /** The tag in which Spring puts the host of an outgoing HTTP call on its metrics. */
  public static final String CLIENT_NAME_TAG = "client.name";

  /** Spring Boot's name of the HTTP client meters. */
  public static final String CLIENT_REQUESTS_NAME =
      "management.observations.http.client.requests.name";

  /** Spring Boot's default name of the HTTP client meters. */
  public static final String DEFAULT_CLIENT_REQUESTS_NAME = "http.client.requests";

  /** The tag, on spans and metrics, in which Spring puts the URI template of an HTTP call. */
  public static final String URI = "uri";

  /**
   * Spring's URL parser, named as text so that a service without Spring's web classes can still
   * read the condition that needs it.
   */
  public static final String URI_COMPONENTS_BUILDER =
      "org.springframework.web.util.UriComponentsBuilder";

  /** The span event that holds an exception's type, message and stack trace. */
  public static final String EXCEPTION_EVENT = "exception";

  /**
   * The span attributes that hold an exception's type: Spring's {@code exception} (the same name as
   * the event) and, with its OpenTelemetry conventions, {@code error.type}. Spans never carry them;
   * metrics keep them.
   */
  public static final Set<String> EXCEPTION_ATTRIBUTES = Set.of(EXCEPTION_EVENT, "error.type");

  /** The bean that keeps exception data out of spans; a bean of this name replaces it. */
  public static final String EXCEPTION_FREE_SPANS = "stratumExceptionFreeSpans";

  /** The name of the timer and span of every run of linked work. */
  public static final String LINKED_WORK_OBSERVATION = "stratum.linked.work";

  /** The tag, on the timer and span of linked work, that holds the work's fixed name. */
  public static final String LINKED_WORK_TAG = "work";

  /** The counter of spans lost because OpenTelemetry's export queue was full. */
  public static final String DROPPED_SPANS_METRIC = "stratum.observability.spans.dropped";

  /** The OpenTelemetry meter that the SDK's batch span processor records its counts in. */
  public static final String SPAN_PROCESSOR_METER = "io.opentelemetry.sdk.trace";

  /** The SDK's counter of spans its batch span processor sent or dropped. */
  public static final String PROCESSED_SPANS_INSTRUMENT = "processedSpans";

  /** The attribute of {@link #PROCESSED_SPANS_INSTRUMENT} that is true for dropped spans. */
  public static final String DROPPED_ATTRIBUTE = "dropped";

  /** Spring Boot's settings of the actuator endpoints served over HTTP, such as their base path. */
  public static final String WEB_ENDPOINTS = "management.endpoints.web";

  /** Spring Boot's settings of the health endpoint, such as its groups. */
  public static final String HEALTH_ENDPOINT = "management.endpoint.health";

  /** The health endpoint's ID, which is also its path when it is not mapped to another. */
  public static final String HEALTH_ENDPOINT_ID = "health";

  /** Spring Boot's switch that also serves the liveness and readiness probes on the main port. */
  public static final String PROBE_ADDITIONAL_PATHS =
      "management.endpoint.health.probes.add-additional-paths";

  /** The main-port paths of the liveness and readiness probes, as Spring Boot serves them. */
  public static final List<String> PROBE_PATHS = List.of("/livez", "/readyz");

  /** The base path of every request on a servlet (MVC) server. */
  public static final String SERVLET_CONTEXT_PATH = "server.servlet.context-path";

  /** The base path of every request on a reactive (WebFlux) server. */
  public static final String WEBFLUX_BASE_PATH = "spring.webflux.base-path";

  /** The base path of every request on a separate management port. */
  public static final String MANAGEMENT_BASE_PATH = "management.server.base-path";
}
