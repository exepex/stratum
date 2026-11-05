package com.exepex.stratum.observability;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
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
 * taken behind the server's base path and behind the management port's. With {@code
 * stratum.observability.tracing.health-probes=true} there are none, so every probe is traced. Read
 * from the environment, not from actuator beans, so a service health check that needs the
 * observation registry does not close a bean cycle.
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
    prefixes =
        ObservabilityProperties.resolve(environment).tracing().healthProbes()
            ? List.of()
            : probePaths(environment);
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
  private static List<String> probePaths(Environment environment) {
    var binder = Binder.get(environment);
    var endpoints =
        binder.bindOrCreate(ObservabilityConstants.WEB_ENDPOINTS, WebEndpointProperties.class);
    var health =
        binder.bindOrCreate(ObservabilityConstants.HEALTH_ENDPOINT, HealthEndpointProperties.class);
    var serverBase =
        basePath(
            environment.getProperty(
                ObservabilityConstants.SERVLET_CONTEXT_PATH,
                environment.getProperty(ObservabilityConstants.WEBFLUX_BASE_PATH, "")));
    var managementBase =
        basePath(environment.getProperty(ObservabilityConstants.MANAGEMENT_BASE_PATH, ""));
    var healthPath =
        endpoints.getBasePath()
            + SLASH
            + StringUtils.trimLeadingCharacter(
                endpoints
                    .getPathMapping()
                    .getOrDefault(
                        ObservabilityConstants.HEALTH_ENDPOINT_ID,
                        ObservabilityConstants.HEALTH_ENDPOINT_ID),
                SLASH);
    var probePaths =
        environment.getProperty(ObservabilityConstants.PROBE_ADDITIONAL_PATHS, Boolean.class, false)
            ? ObservabilityConstants.PROBE_PATHS.stream().map(serverBase::concat)
            : Stream.<String>empty();
    var groupPaths =
        health.getGroup().values().stream()
            .map(HealthEndpointProperties.Group::getAdditionalPath)
            .filter(Objects::nonNull)
            .map(AdditionalHealthEndpointPath::from)
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
   * Returns a base path as Spring Boot cleans it: with a leading slash and without a trailing one,
   * or empty, so a path can follow it.
   */
  private static String basePath(String path) {
    var trimmed = StringUtils.trimTrailingCharacter(path, SLASH);
    return trimmed.isEmpty() || trimmed.charAt(0) == SLASH ? trimmed : SLASH + trimmed;
  }
}
