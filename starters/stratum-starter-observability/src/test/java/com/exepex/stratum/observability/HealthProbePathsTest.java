package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("HealthProbePaths finds the health and probe paths in Spring Boot's settings")
class HealthProbePathsTest {

  /** The health endpoint's path with Spring Boot's defaults. */
  private static final String HEALTH_PATH = "/actuator/health";

  /** A base path a service sets for its server or its management port. */
  private static final String BASE_PATH = "/svc";

  /** The additional path of a health group a service adds. */
  private static final String GROUP_PATH = "/ping";

  /** The health endpoint and its groups' paths, which are probe paths. */
  private static final List<String> HEALTH_PATHS =
      List.of(HEALTH_PATH, HEALTH_PATH + "/liveness", HEALTH_PATH + "/readiness");

  /** Paths that are not probe paths, some starting with the same letters. */
  private static final List<String> OTHER_PATHS =
      List.of("/actuator/healthy", "/actuator", "/orders", "/orders" + HEALTH_PATH);

  /** The health endpoint's mapping, with and without its leading slash. */
  private static final List<String> HEALTH_MAPPINGS = List.of("healthz", "/healthz");

  /** The keys of the server's and the management port's base paths. */
  private static final List<String> BASE_PATH_KEYS =
      List.of(
          "server.servlet.context-path", "spring.webflux.base-path", "management.server.base-path");

  /** A base path written with a trailing slash, and without its leading one. */
  private static final List<String> UNCLEAN_BASE_PATHS = List.of(BASE_PATH + "/", "svc");

  private final MockEnvironment environment = new MockEnvironment();

  /** Returns whether the path is a probe path with the test's settings. */
  private boolean contains(String path) {
    return new HealthProbePaths(environment).contains(path);
  }

  @ParameterizedTest
  @FieldSource("HEALTH_PATHS")
  @DisplayName("the health endpoint and its groups are probe paths")
  void containsHealthPaths(String path) {
    assertThat(contains(path)).isTrue();
  }

  @ParameterizedTest
  @FieldSource("OTHER_PATHS")
  @DisplayName("other paths, even ones that start with the same letters, are not")
  void leavesOtherPaths(String path) {
    assertThat(contains(path)).isFalse();
  }

  @Test
  @DisplayName("the endpoints' base path is followed, the root included")
  void followsEndpointBasePath() {
    environment.setProperty("management.endpoints.web.base-path", "/");

    assertThat(contains("/health/liveness")).isTrue();
  }

  @ParameterizedTest
  @FieldSource("HEALTH_MAPPINGS")
  @DisplayName("the health endpoint's mapping is followed, with or without its leading slash")
  void followsHealthMapping(String mapping) {
    environment.setProperty("management.endpoints.web.path-mapping.health", mapping);

    assertThat(contains("/actuator/healthz/liveness")).isTrue();
  }

  @ParameterizedTest
  @FieldSource("BASE_PATH_KEYS")
  @DisplayName("the server's or the management port's base path comes first")
  void followsServerBasePath(String key) {
    environment.setProperty(key, BASE_PATH);

    assertThat(contains(BASE_PATH + HEALTH_PATH)).isTrue();
  }

  @ParameterizedTest
  @FieldSource("UNCLEAN_BASE_PATHS")
  @DisplayName(
      "a base path is cleaned as Spring Boot cleans it, with one leading and no trailing /")
  void cleansBasePath(String basePath) {
    environment.setProperty("spring.webflux.base-path", basePath);

    assertThat(contains(BASE_PATH + HEALTH_PATH)).isTrue();
  }

  @Test
  @DisplayName("/livez and /readyz are probe paths when Spring Boot serves them on the main port")
  void containsMainPortProbes() {
    environment
        .withProperty("management.endpoint.health.probes.add-additional-paths", "true")
        .setProperty("server.servlet.context-path", BASE_PATH);

    assertThat(contains(BASE_PATH + "/livez")).isTrue();
    assertThat(contains(BASE_PATH + "/readyz")).isTrue();
  }

  @Test
  @DisplayName("/livez is an ordinary path when Spring Boot does not serve it")
  void leavesUnservedProbes() {
    assertThat(contains("/livez")).isFalse();
  }

  @Test
  @DisplayName("a health group's additional path on the main port is a probe path")
  void containsServerGroupPath() {
    environment
        .withProperty(
            "management.endpoint.health.group.ping.additional-path", "server:" + GROUP_PATH)
        .setProperty("server.servlet.context-path", BASE_PATH);

    assertThat(contains(BASE_PATH + GROUP_PATH)).isTrue();
  }

  @Test
  @DisplayName("a health group's additional path on the management port is a probe path")
  void containsManagementGroupPath() {
    environment
        .withProperty(
            "management.endpoint.health.group.ping.additional-path", "management:" + GROUP_PATH)
        .setProperty("management.server.base-path", BASE_PATH);

    assertThat(contains(BASE_PATH + GROUP_PATH)).isTrue();
  }

  @Test
  @DisplayName("with health-probes=true there are no probe paths, so probes are traced")
  void containsNothingWhenProbesAreTraced() {
    environment.setProperty("stratum.observability.tracing.health-probes", "true");

    assertThat(contains(HEALTH_PATH)).isFalse();
  }
}
