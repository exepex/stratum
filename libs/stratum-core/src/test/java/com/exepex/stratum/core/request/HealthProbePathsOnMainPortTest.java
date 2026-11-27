package com.exepex.stratum.core.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName(
    "HealthProbePaths.onMainPort holds the main port's actuator paths within the application")
class HealthProbePathsOnMainPortTest {

  /** The endpoint paths actuator serves with Spring Boot's defaults. */
  private static final List<String> DEFAULT_ENDPOINT_PATHS =
      List.of("/actuator/health", "/actuator/info");

  /** The key of the endpoints' base path. */
  private static final String BASE_PATH = "management.endpoints.web.base-path";

  /** The key of Spring Boot's switch that serves /livez and /readyz on the main port. */
  private static final String PROBES_ON_MAIN_PORT =
      "management.endpoint.health.probes.add-additional-paths";

  /** The key of a health group's additional path. */
  private static final String GROUP_PATH = "management.endpoint.health.group.ping.additional-path";

  /** A service route, which is never an actuator path. */
  private static final String SERVICE_ROUTE = "/reports/1";

  private final MockEnvironment environment = new MockEnvironment();

  /** Returns whether the path is an actuator path of the main port with the test's settings. */
  private boolean contains(Collection<String> endpointPaths, String path) {
    return HealthProbePaths.onMainPort(environment, endpointPaths).contains(path);
  }

  @Nested
  @DisplayName("with the default base path /actuator")
  class DefaultBasePath {

    /** The discovery page, the endpoints and paths below them, all on the main port. */
    private static final List<String> ACTUATOR_PATHS =
        List.of("/actuator", "/actuator/health", "/actuator/health/liveness", "/actuator/info");

    /** Paths that only start with the same letters, and service routes. */
    private static final List<String> OTHER_PATHS =
        List.of("/actuatorx", "/actuatorx/health", SERVICE_ROUTE, "/");

    @ParameterizedTest
    @FieldSource("ACTUATOR_PATHS")
    @DisplayName("the discovery page and every endpoint below it are held")
    void holdsActuatorPaths(String path) {
      assertThat(contains(DEFAULT_ENDPOINT_PATHS, path)).isTrue();
    }

    @ParameterizedTest
    @FieldSource("OTHER_PATHS")
    @DisplayName("other paths are not")
    void leavesOtherPaths(String path) {
      assertThat(contains(DEFAULT_ENDPOINT_PATHS, path)).isFalse();
    }
  }

  @Nested
  @DisplayName("with another base path")
  class OtherBasePath {

    @Test
    @DisplayName("base path /manage holds /manage and /manage/health, not /managex")
    void holdsBasePathAndBelow() {
      environment.setProperty(BASE_PATH, "/manage");
      var endpointPaths = List.of("/manage/health");

      assertThat(contains(endpointPaths, "/manage")).isTrue();
      assertThat(contains(endpointPaths, "/manage/health")).isTrue();
      assertThat(contains(endpointPaths, "/managex")).isFalse();
    }

    @Test
    @DisplayName("the root base path holds each endpoint's own path, never / or a service route")
    void rootBasePathHoldsOnlyEndpoints() {
      environment.setProperty(BASE_PATH, "/");
      var endpointPaths = List.of("/health", "/info");

      assertThat(contains(endpointPaths, "/health")).isTrue();
      assertThat(contains(endpointPaths, "/info")).isTrue();
      assertThat(contains(endpointPaths, SERVICE_ROUTE)).isFalse();
      assertThat(contains(endpointPaths, "/")).isFalse();
    }

    @Test
    @DisplayName(
        "a given endpoint path of / or empty is left out: GET / and every route stay counted")
    void dropsRootEndpointPath() {
      environment.setProperty(BASE_PATH, "/");

      assertThat(contains(List.of("/", ""), SERVICE_ROUTE)).isFalse();
      assertThat(contains(List.of("/", ""), "/")).isFalse();
    }
  }

  @Nested
  @DisplayName("with probe and group paths")
  class ProbeAndGroupPaths {

    @Test
    @DisplayName("/livez and /readyz are held when Spring Boot serves them on the main port")
    void holdsMainPortProbes() {
      environment.setProperty(PROBES_ON_MAIN_PORT, "true");

      assertThat(contains(List.of(), "/livez")).isTrue();
      assertThat(contains(List.of(), "/readyz")).isTrue();
    }

    @Test
    @DisplayName("/livez is a service route when Spring Boot does not serve it")
    void leavesUnservedProbes() {
      assertThat(contains(List.of(), "/livez")).isFalse();
    }

    @Test
    @DisplayName("a group's server: path is held")
    void holdsServerGroupPath() {
      environment.setProperty(GROUP_PATH, "server:/healthz");

      assertThat(contains(List.of(), "/healthz")).isTrue();
    }

    @Test
    @DisplayName("a group's management: path is not, as only the management port serves it")
    void leavesManagementGroupPath() {
      environment.setProperty(GROUP_PATH, "management:/healthz");

      assertThat(contains(List.of(), "/healthz")).isFalse();
    }
  }

  @Nested
  @DisplayName("with the management port")
  class ManagementPort {

    @Test
    @DisplayName("a different port holds no actuator path, only the main port's probes")
    void differentPortHoldsNoActuatorPath() {
      environment
          .withProperty("server.port", "8080")
          .withProperty("management.server.port", "9090")
          .setProperty(PROBES_ON_MAIN_PORT, "true");

      assertThat(contains(DEFAULT_ENDPOINT_PATHS, "/actuator/x")).isFalse();
      assertThat(contains(DEFAULT_ENDPOINT_PATHS, "/actuator/health")).isFalse();
      assertThat(contains(DEFAULT_ENDPOINT_PATHS, "/livez")).isTrue();
    }

    @Test
    @DisplayName("the same port as the server's holds the actuator paths")
    void samePortHoldsActuatorPaths() {
      environment.withProperty("server.port", "8081").setProperty("management.server.port", "8081");

      assertThat(contains(DEFAULT_ENDPOINT_PATHS, "/actuator/info")).isTrue();
    }
  }
}
