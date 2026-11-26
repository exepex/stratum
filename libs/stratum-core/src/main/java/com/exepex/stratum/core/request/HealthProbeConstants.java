package com.exepex.stratum.core.request;

import java.util.List;
import lombok.experimental.UtilityClass;

/** Spring Boot's property keys and paths that {@link HealthProbePaths} reads (ST-ENG-45). */
@UtilityClass
class HealthProbeConstants {

  /** Spring Boot's settings of the actuator endpoints served over HTTP, such as their base path. */
  static final String WEB_ENDPOINTS = "management.endpoints.web";

  /** Spring Boot's settings of the health endpoint, such as its groups. */
  static final String HEALTH_ENDPOINT = "management.endpoint.health";

  /** The health endpoint's ID, which is also its path when it is not mapped to another. */
  static final String HEALTH_ENDPOINT_ID = "health";

  /** Spring Boot's switch that also serves the liveness and readiness probes on the main port. */
  static final String PROBE_ADDITIONAL_PATHS =
      "management.endpoint.health.probes.add-additional-paths";

  /** The main-port paths of the liveness and readiness probes, as Spring Boot serves them. */
  static final List<String> PROBE_PATHS = List.of("/livez", "/readyz");

  /** The base path of every request on a servlet (MVC) server. */
  static final String SERVLET_CONTEXT_PATH = "server.servlet.context-path";

  /** The base path of every request on a reactive (WebFlux) server. */
  static final String WEBFLUX_BASE_PATH = "spring.webflux.base-path";

  /** The base path of every request on a separate management port. */
  static final String MANAGEMENT_BASE_PATH = "management.server.base-path";
}
