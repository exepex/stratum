package com.exepex.stratum.web;

import com.exepex.stratum.core.properties.StratumCustomizers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.Environment;

/**
 * The settings of the web module, bound to {@code stratum.web.*} and then changed by the service's
 * {@link WebPropertiesCustomizer} classes, which win. Both web starters read the same settings. The
 * descriptions of the components are what IDEs show, so they are plain sentences.
 *
 * @param error How errors are answered.
 * @param correlation Where each request's correlation ID comes from.
 * @param clientIp How the client's IP address is found behind proxies.
 * @param userAgent What is read from the client's user agent.
 * @param clientLocation Where the client's country and city come from.
 * @param requestFields The fields every log line of a request carries.
 * @param requestLog The one log line each request writes when it ends.
 * @param timeout How long a request may take before it is answered with the timeout status.
 */
@Builder(toBuilder = true)
@ConfigurationProperties(prefix = WebConstants.PREFIX)
public record WebProperties(
    @DefaultValue @Valid ErrorSettings error,
    @DefaultValue CorrelationSettings correlation,
    @DefaultValue @Valid ClientIpSettings clientIp,
    @DefaultValue @Valid UserAgentSettings userAgent,
    @DefaultValue ClientLocationSettings clientLocation,
    @DefaultValue @Valid RequestFieldsSettings requestFields,
    @DefaultValue @Valid RequestLogSettings requestLog,
    @DefaultValue @Valid TimeoutSettings timeout) {

  /**
   * Returns the settings the module uses: {@code stratum.web.*} with the defaults for anything
   * unset, changed by every {@link WebPropertiesCustomizer} in {@code META-INF/spring.factories},
   * then validated.
   *
   * @param environment the application's environment
   * @return the settings
   * @throws jakarta.validation.ConstraintViolationException {@code SX-CORE-1003} if a setting
   *     breaks its rules
   */
  public static WebProperties resolve(Environment environment) {
    return StratumCustomizers.resolveSettings(
        environment, WebConstants.PREFIX, WebProperties.class, WebPropertiesCustomizer.class);
  }

  /**
   * How errors are answered.
   *
   * @param statuses The HTTP status, from 400 to 599, of each of the service's error codes that a
   *     StratumException carries, such as ORD-0001 to 409. A code with no status is answered with
   *     500 and logged. Defaults to none.
   */
  @Builder(toBuilder = true)
  public record ErrorSettings(@DefaultValue Map<String, @Min(400) @Max(599) Integer> statuses) {

    /** Keeps an unchangeable copy of the statuses. */
    public ErrorSettings {
      statuses = Map.copyOf(statuses);
    }
  }

  /**
   * Where each request's correlation ID comes from.
   *
   * @param acceptFromCaller Whether the caller's X-Correlation-Id header, or the correlationId it
   *     sent in the tracing baggage, is used when it is 1 to 128 letters, digits, -, _, . or :. Set
   *     it to false on a service that faces the internet, so that every request's correlation ID is
   *     its trace ID. Defaults to true.
   */
  @Builder(toBuilder = true)
  public record CorrelationSettings(@DefaultValue("true") boolean acceptFromCaller) {}

  /**
   * How the client's IP address is found behind proxies.
   *
   * @param trustedProxies The address ranges, such as 10.0.0.0/8, of the load balancers and proxies
   *     in front of the service. Their X-Forwarded-For header is read from right to left, and the
   *     first address outside these ranges is the client's. A request from any other address is
   *     taken as it arrives. Defaults to the private and loopback ranges: 10.0.0.0/8,
   *     172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8, ::1/128 and fc00::/7.
   */
  @Builder(toBuilder = true)
  public record ClientIpSettings(List<@Pattern(regexp = ADDRESS_RANGE) String> trustedProxies) {

    /** An IPv4 or IPv6 address, a slash and a prefix length. */
    private static final String ADDRESS_RANGE = "[0-9A-Fa-f.:]+/[0-9]{1,3}";

    /** The private and loopback ranges, the ranges of a service's own network. */
    private static final List<String> PRIVATE_ADDRESS_RANGES =
        List.of(
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "::1/128", "fc00::/7");

    /**
     * Keeps an unchangeable copy of the ranges, which are the private ranges when unset: Spring
     * Boot passes null for a list that is not set.
     */
    public ClientIpSettings {
      trustedProxies =
          trustedProxies == null ? PRIVATE_ADDRESS_RANGES : List.copyOf(trustedProxies);
    }
  }

  /**
   * What is read from the client's user agent. The raw User-Agent header is always logged.
   *
   * @param parse Whether the device, operating system and browser are read from the User-Agent
   *     header and the browser's client hints, and logged as separate fields. Turning it on adds
   *     about 110 MB of memory and 3 seconds of startup. Defaults to false.
   * @param cacheSize How many different user agents are kept already parsed, from 1 to 1000000.
   *     Defaults to 10000.
   * @param requestClientHints Whether responses ask browsers for their device model and full
   *     operating system version, with the Accept-CH header, so later requests can log them. Only
   *     used when parse is true. Defaults to false.
   */
  @Builder(toBuilder = true)
  public record UserAgentSettings(
      @DefaultValue("false") boolean parse,
      @DefaultValue("10000") @Min(1) @Max(1_000_000) int cacheSize,
      @DefaultValue("false") boolean requestClientHints) {}

  /**
   * Where the client's country and city come from.
   *
   * @param database The MaxMind GeoIP2 or GeoLite2 City or Country database file, in .mmdb format,
   *     that your team provides and keeps up to date. Defaults to none, which logs no country or
   *     city.
   */
  @Builder(toBuilder = true)
  public record ClientLocationSettings(@Nullable Path database) {}

  /**
   * The fields every log line of a request carries.
   *
   * @param maxValueLength The most characters a field keeps, such as a long query string or user
   *     agent, from 16 to 8192; the rest is cut off. Defaults to 512.
   */
  @Builder(toBuilder = true)
  public record RequestFieldsSettings(
      @DefaultValue("512") @Min(16) @Max(8192) int maxValueLength) {}

  /**
   * The one log line each request writes when it ends.
   *
   * @param enabled Whether each request writes one INFO log line when it ends, with its status, how
   *     long it took and the headers named below. Requests for static files never write one.
   *     Defaults to true.
   * @param skipPaths The path patterns whose requests write no line, such as /actuator/** or
   *     /internal/*. Setting the list replaces the default, so keep /actuator/** in it. Defaults to
   *     /actuator/**.
   * @param requestHeaders The request headers to log, each with the field name it is logged under,
   *     such as X-Tenant-Id to tenantId. A field name is a letter, then up to 63 letters, digits, _
   *     or . characters. Authorization, Cookie, Set-Cookie, X-Api-Key and the denied headers are
   *     never logged. Defaults to none.
   * @param responseHeaders The response headers to log, each with the field name it is logged
   *     under, as for the request headers. Defaults to none.
   * @param deniedHeaders More headers that are never logged, even when listed above, such as a
   *     header your platform uses for a secret. Defaults to none.
   * @param tokenPayload Whether the claims named in token-claims, from the payload of the caller's
   *     bearer JSON Web Token, are logged as jwtClaims. They are decoded but not verified, so never
   *     trust them. The token's header, signature and other claims never are. Defaults to false.
   * @param tokenClaims The claims of the caller's token that are logged when token-payload is true.
   *     Setting the list replaces the default; list no claim that holds personal data, such as
   *     email or name. Defaults to sub, azp, client_id and scope.
   */
  @Builder(toBuilder = true)
  public record RequestLogSettings(
      @DefaultValue("true") boolean enabled,
      List<String> skipPaths,
      @DefaultValue Map<String, @Pattern(regexp = FIELD_NAME) String> requestHeaders,
      @DefaultValue Map<String, @Pattern(regexp = FIELD_NAME) String> responseHeaders,
      @DefaultValue List<String> deniedHeaders,
      @DefaultValue("false") boolean tokenPayload,
      List<String> tokenClaims) {

    /** A field name a log platform accepts. */
    private static final String FIELD_NAME = "[A-Za-z][A-Za-z0-9_.]{0,63}";

    /** The token claims logged when none are set: who called, for which client and scope. */
    private static final List<String> CALLER_CLAIMS = List.of("sub", "azp", "client_id", "scope");

    /** The paths skipped when none are set: the actuator's endpoints, such as health checks. */
    private static final List<String> ACTUATOR_PATHS = List.of("/actuator/**");

    /**
     * Keeps unchangeable copies, with the defaults for the skip paths and token claims when unset:
     * Spring Boot passes null for a list that is not set.
     */
    public RequestLogSettings {
      skipPaths = skipPaths == null ? ACTUATOR_PATHS : List.copyOf(skipPaths);
      tokenClaims = tokenClaims == null ? CALLER_CLAIMS : List.copyOf(tokenClaims);
      requestHeaders = Map.copyOf(requestHeaders);
      responseHeaders = Map.copyOf(responseHeaders);
      deniedHeaders = List.copyOf(deniedHeaders);
    }
  }

  /**
   * How long a request may take until its response starts, before it is answered with the timeout
   * status. A response that has started, such as a stream or a download, is not cut off. A method's
   * {@code @ResponseTimeout} wins over a route, and a route wins over the default.
   *
   * @param defaultLimit How long any request may take until its response starts, such as 10s or
   *     500ms. 0 means no limit. Defaults to 10s.
   * @param routes The limit of the requests whose path matches a pattern, such as /reports/** to
   *     60s. When several patterns match, the most specific one wins. 0 means no limit on those
   *     paths. Defaults to none.
   * @param status The HTTP status, from 400 to 599, that a request which ran out of time is
   *     answered with. Defaults to 503.
   */
  @Builder(toBuilder = true)
  public record TimeoutSettings(
      @DefaultValue("10s") Duration defaultLimit,
      @DefaultValue Map<String, Duration> routes,
      @DefaultValue("503") @Min(400) @Max(599) int status) {

    /** Keeps an unchangeable copy of the routes. */
    public TimeoutSettings {
      routes = Map.copyOf(routes);
    }
  }
}
