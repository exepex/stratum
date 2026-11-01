package com.exepex.stratum.httpclient;

import com.exepex.stratum.core.properties.StratumCustomizers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import lombok.Builder;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.Environment;

/**
 * The settings of the HTTP client module, bound to {@code stratum.http-client.*} and then changed
 * by the service's {@link HttpClientPropertiesCustomizer} classes, which win. They hold only what
 * Spring Boot has no key for; a client's address and timeouts use Spring Boot's own keys. The
 * descriptions of the components are what IDEs show, so they are plain sentences.
 *
 * @param pool How many connections the clients keep open, and how long a call waits for one.
 */
@Builder(toBuilder = true)
@ConfigurationProperties(prefix = HttpClientConstants.PREFIX)
public record HttpClientProperties(@DefaultValue @Valid PoolSettings pool) {

  /**
   * Returns the settings the module uses: {@code stratum.http-client.*} with the defaults for
   * anything unset, changed by every {@link HttpClientPropertiesCustomizer} in {@code
   * META-INF/spring.factories}, then validated.
   *
   * @param environment the application's environment
   * @return the settings
   * @throws jakarta.validation.ConstraintViolationException {@code SX-CORE-1003} if a setting
   *     breaks its rules
   */
  public static HttpClientProperties resolve(Environment environment) {
    return StratumCustomizers.resolveSettings(
        environment,
        HttpClientConstants.PREFIX,
        HttpClientProperties.class,
        HttpClientPropertiesCustomizer.class);
  }

  /**
   * How many connections the clients keep open, so that a slow downstream service cannot use up the
   * service's sockets and threads.
   *
   * @param maxConnections The most connections a pool keeps open at once to one downstream service,
   *     from 1 to 10000. The reactive clients share one pool; each blocking HTTP interface group,
   *     and each RestClient built from Spring Boot's builder, has its own. A call that finds them
   *     all busy waits for one. Defaults to 50.
   * @param maxWait How long a call waits for a free connection before it fails, such as 2s or
   *     500ms. Defaults to 2s.
   */
  @Builder(toBuilder = true)
  public record PoolSettings(
      @DefaultValue("50") @Min(1) @Max(10_000) int maxConnections,
      @DefaultValue("2s") @DurationMin(millis = 1) Duration maxWait) {}
}
