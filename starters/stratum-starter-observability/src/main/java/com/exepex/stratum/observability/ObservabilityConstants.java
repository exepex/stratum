package com.exepex.stratum.observability;

import com.exepex.stratum.core.condition.StratumModuleConstants;
import java.util.List;
import lombok.experimental.UtilityClass;

/** The property keys, tag names and span attributes the observability module uses (ST-ENG-45). */
@UtilityClass
public class ObservabilityConstants {

  /** The module's name, as in {@code stratum.observability.enabled}. */
  public static final String MODULE = "observability";

  /** The prefix of the module's own settings. */
  public static final String PREFIX = StratumModuleConstants.PREFIX + "." + MODULE;

  /** The switch that turns the module on. */
  public static final String ENABLED = StratumModuleConstants.enabledProperty(MODULE);

  /** The service's name, which Spring Boot also sends as the {@code service.name} resource. */
  public static final String APPLICATION_NAME = "spring.application.name";

  /** The environment variable that holds the host's name; in Kubernetes, the pod's name. */
  public static final String HOSTNAME = "HOSTNAME";

  /** Spring Boot's list of actuator endpoints reachable over HTTP. */
  public static final String WEB_EXPOSURE = "management.endpoints.web.exposure.include";

  /** The actuator endpoints Stratum exposes over HTTP by default (ST-SEC-10). */
  public static final String SAFE_ENDPOINTS = "health,info";

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
      "management.opentelemetry.resource-attributes[deployment.environment.name]";

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

  /**
   * Spring Boot's switches that trace and time Kafka and RabbitMQ messages, which it leaves off.
   * JMS needs none: Spring Boot observes it whenever metrics or tracing are set up.
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

  /** The span attribute in which Spring puts the full URL of an outgoing HTTP call. */
  public static final String HTTP_URL = "http.url";

  /** The tag, on spans and metrics, in which Spring puts the URI template of an HTTP call. */
  public static final String URI = "uri";

  /**
   * Spring's URL parser, named as text so that a service without Spring's web classes can still
   * read the condition that needs it.
   */
  public static final String URI_COMPONENTS_BUILDER =
      "org.springframework.web.util.UriComponentsBuilder";
}
