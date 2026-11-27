package com.exepex.stratum.resilience;

import com.exepex.stratum.core.properties.StratumCustomizers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.Environment;

/**
 * The settings of the resilience module, bound to {@code stratum.resilience.*} and then changed by
 * the service's {@link ResiliencePropertiesCustomizer} classes, which win. The descriptions of the
 * components are what IDEs show, so they are plain sentences.
 *
 * @param incoming The limits on the work that comes into the service: its Spring MVC requests.
 */
@Builder(toBuilder = true)
@ConfigurationProperties(prefix = ResilienceConstants.PREFIX)
public record ResilienceProperties(@DefaultValue @Valid IncomingSettings incoming) {

  /**
   * Returns the settings the module uses: {@code stratum.resilience.*} with the defaults for
   * anything unset, changed by every {@link ResiliencePropertiesCustomizer} in {@code
   * META-INF/spring.factories}, then validated.
   *
   * @param environment the application's environment
   * @return the settings
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-CORE-1003} if a setting breaks
   *     its rules
   */
  public static ResilienceProperties resolve(Environment environment) {
    return StratumCustomizers.resolveSettings(
        environment,
        ResilienceConstants.PREFIX,
        ResilienceProperties.class,
        ResiliencePropertiesCustomizer.class);
  }

  /**
   * The limits on the work that comes into the service: its Spring MVC requests.
   *
   * @param routes The limit of each route, by path pattern within the application, such as
   *     /reports/**. A request on the most specific matching route is refused with
   *     SX-RESILIENCE-0001, 503 by default, while the route already runs max-concurrent requests.
   *     Limits count per instance. No route, so no limit, by default.
   * @param retryAfter How long a refused caller is told to wait, in the Retry-After header, in
   *     whole seconds rounded up: from 1s to 1h. Defaults to 1s.
   */
  @Builder(toBuilder = true)
  public record IncomingSettings(
      @DefaultValue Map<String, @Valid RouteLimit> routes,
      @DefaultValue("1s") @NotNull Duration retryAfter) {

    /** The shortest wait a refused caller is told. */
    private static final Duration MIN_RETRY_AFTER = Duration.ofSeconds(1);

    /** The longest wait a refused caller is told. */
    private static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);

    /** Nanoseconds in a second, to round a wait up to whole seconds. */
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /**
     * Keeps an unchangeable copy of the routes, which are none when a Java customizer's builder
     * leaves them out.
     */
    public IncomingSettings {
      routes = routes == null ? Map.of() : Map.copyOf(routes);
    }

    /**
     * Returns whether {@code retry-after} is from 1s to 1h; checked at startup, where a value out
     * of range breaks {@code @AssertTrue}.
     *
     * @return whether the wait is in range
     */
    @AssertTrue
    public boolean isRetryAfterInRange() {
      return retryAfter == null
          || retryAfter.compareTo(MIN_RETRY_AFTER) >= 0
              && retryAfter.compareTo(MAX_RETRY_AFTER) <= 0;
    }

    /**
     * Returns the value of the {@code Retry-After} header: the wait in whole seconds, rounded up.
     *
     * @return the seconds, as text
     */
    public String retryAfterSeconds() {
      return String.valueOf(retryAfter.plusNanos(NANOS_PER_SECOND - 1).getSeconds());
    }
  }

  /**
   * The limit of one route.
   *
   * @param maxConcurrent The most requests of the route that run at once, from 1 to 100000; more
   *     are refused at once and never wait. Required for each route.
   */
  @Builder(toBuilder = true)
  public record RouteLimit(
      @Min(1) @Max(ResilienceConstants.MAX_CONCURRENT_LIMIT) int maxConcurrent) {}
}
