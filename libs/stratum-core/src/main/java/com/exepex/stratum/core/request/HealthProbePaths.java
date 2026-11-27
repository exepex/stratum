package com.exepex.stratum.core.request;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointProperties;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * The request paths of the service's health endpoint and probes, read from Spring Boot's settings:
 * the health endpoint under its base path and mapping, each health group's additional path, and
 * {@code /livez} and {@code /readyz} when Spring Boot serves them on the main port. Each path is
 * taken behind the server's base path and behind the management port's. Read from the environment,
 * not from actuator beans, so a service health check that needs the observation registry does not
 * close a bean cycle. {@link #onMainPort} instead holds the paths within the application that the
 * main port serves to actuator, for a module that must never count them.
 */
@StratumInternal
public final class HealthProbePaths {

  private static final char SLASH = '/';

  private final List<String> prefixes;

  /**
   * Reads the paths from the service's settings.
   *
   * @param environment the service's environment
   */
  public HealthProbePaths(Environment environment) {
    prefixes = probePaths(environment);
  }

  /** Holds no path, for a module that treats probes like any other request. */
  private HealthProbePaths() {
    prefixes = List.of();
  }

  /** Reads the paths within the application that the main port serves to actuator. */
  private HealthProbePaths(Environment environment, Collection<String> actuatorEndpointPaths) {
    prefixes = mainPortPaths(environment, actuatorEndpointPaths);
  }

  /**
   * Returns paths that contain nothing, so every request, probes included, is handled alike.
   *
   * @return the empty paths
   */
  public static HealthProbePaths none() {
    return new HealthProbePaths();
  }

  /**
   * Returns the paths within the application, without its context path, that the main port serves
   * to actuator: each health group's {@code server:} path, {@code /livez} and {@code /readyz} when
   * Spring Boot serves them there, and, when actuator shares the main port, the health endpoint,
   * the endpoints' base path with its discovery page, and each given endpoint's path. A path that
   * is empty or {@code /}, such as the root base path, is left out, so it never holds every
   * request.
   *
   * @param environment the service's environment
   * @param actuatorEndpointPaths the path of each endpoint actuator serves, such as {@code
   *     /actuator/info}; empty without actuator
   * @return the paths
   */
  public static HealthProbePaths onMainPort(
      Environment environment, Collection<String> actuatorEndpointPaths) {
    return new HealthProbePaths(environment, actuatorEndpointPaths);
  }

  /**
   * Returns whether a request path is one of the paths, or below one.
   *
   * @param path the request's path
   * @return {@code true} for a health or probe request
   */
  public boolean contains(String path) {
    return prefixes.stream()
        .anyMatch(
            prefix ->
                path.startsWith(prefix)
                    && (path.length() == prefix.length() || path.charAt(prefix.length()) == SLASH));
  }

  /** Returns every probe path, each behind the server's and the management port's base path. */
  private List<String> probePaths(Environment environment) {
    var binder = Binder.get(environment);
    var serverBase =
        basePath(
            environment.getProperty(
                HealthProbeConstants.SERVLET_CONTEXT_PATH,
                environment.getProperty(HealthProbeConstants.WEBFLUX_BASE_PATH, "")));
    var managementBase =
        basePath(environment.getProperty(HealthProbeConstants.MANAGEMENT_BASE_PATH, ""));
    var healthPath = healthPath(webEndpoints(binder));
    var probePaths = mainPortProbePaths(environment).map(serverBase::concat);
    var groupPaths =
        groupPaths(binder)
            .map(
                path ->
                    (path.hasNamespace(WebServerNamespace.MANAGEMENT) ? managementBase : serverBase)
                        + path.getValue());
    return Stream.of(
            Stream.of(serverBase + healthPath, managementBase + healthPath), probePaths, groupPaths)
        .flatMap(Function.identity())
        .distinct()
        .toList();
  }

  /**
   * Returns the main port's actuator paths within the application, leaving out the management
   * port's group paths and, when actuator has its own port, every actuator path.
   */
  private List<String> mainPortPaths(
      Environment environment, Collection<String> actuatorEndpointPaths) {
    var binder = Binder.get(environment);
    var endpoints = webEndpoints(binder);
    var groupPaths =
        groupPaths(binder)
            .filter(path -> !path.hasNamespace(WebServerNamespace.MANAGEMENT))
            .map(AdditionalHealthEndpointPath::getValue);
    var actuatorPaths =
        ManagementPortType.get(environment) == ManagementPortType.SAME
            ? Stream.concat(
                Stream.of(healthPath(endpoints), endpoints.getBasePath()),
                actuatorEndpointPaths.stream())
            : Stream.<String>empty();
    return Stream.of(mainPortProbePaths(environment), groupPaths, actuatorPaths)
        .flatMap(Function.identity())
        .filter(path -> !path.isEmpty() && !path.equals(String.valueOf(SLASH)))
        .distinct()
        .toList();
  }

  /** Returns Spring Boot's settings of the actuator endpoints served over HTTP. */
  private WebEndpointProperties webEndpoints(Binder binder) {
    return binder.bindOrCreate(HealthProbeConstants.WEB_ENDPOINTS, WebEndpointProperties.class);
  }

  /** Returns the health endpoint's path: the endpoints' base path and the health mapping. */
  private String healthPath(WebEndpointProperties endpoints) {
    return endpoints.getBasePath()
        + SLASH
        + StringUtils.trimLeadingCharacter(
            endpoints
                .getPathMapping()
                .getOrDefault(
                    HealthProbeConstants.HEALTH_ENDPOINT_ID,
                    HealthProbeConstants.HEALTH_ENDPOINT_ID),
            SLASH);
  }

  /** Returns {@code /livez} and {@code /readyz} when Spring Boot serves them on the main port. */
  private Stream<String> mainPortProbePaths(Environment environment) {
    return Boolean.parseBoolean(
            environment.getProperty(HealthProbeConstants.PROBE_ADDITIONAL_PATHS))
        ? HealthProbeConstants.PROBE_PATHS.stream()
        : Stream.empty();
  }

  /** Returns the additional path of each health group that has one. */
  private Stream<AdditionalHealthEndpointPath> groupPaths(Binder binder) {
    return binder
        .bindOrCreate(HealthProbeConstants.HEALTH_ENDPOINT, HealthEndpointProperties.class)
        .getGroup()
        .values()
        .stream()
        .map(HealthEndpointProperties.Group::getAdditionalPath)
        .filter(Objects::nonNull)
        .map(AdditionalHealthEndpointPath::from);
  }

  /**
   * Returns a base path as Spring Boot cleans it: with a leading slash and without a trailing one,
   * or empty, so a path can follow it.
   */
  private String basePath(String path) {
    var withoutTrailing =
        path.endsWith(String.valueOf(SLASH)) ? path.substring(0, path.length() - 1) : path;
    return withoutTrailing.isEmpty() || withoutTrailing.startsWith(String.valueOf(SLASH))
        ? withoutTrailing
        : SLASH + withoutTrailing;
  }
}
