package com.exepex.stratum.data;

import com.exepex.stratum.core.properties.StratumCustomizers;
import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

/**
 * The settings of the data module, bound to {@code stratum.data.*} and then changed by the
 * service's {@link DataPropertiesCustomizer} classes, which win. The descriptions of the components
 * are what IDEs show, so they are plain sentences.
 *
 * @param queryTimeout How long one SQL statement may run before Stratum cancels it, when the code
 *     that runs it sets no time limit of its own. A statement in a transaction with a time limit
 *     ends by that limit too. 0 means no limit. Defaults to 30s, or to 0 with
 *     stratum.backward-compatible=true.
 * @param deadlineMargin How much earlier than the web request's time limit its statements and
 *     connection borrows must end, so that a slow statement is cancelled and answered before the
 *     web module has to interrupt the request. A statement in a web request with a time limit ends
 *     by that limit minus this margin. Defaults to 150ms.
 */
@Builder(toBuilder = true)
@ConfigurationProperties(prefix = DataConstants.PREFIX)
public record DataProperties(@Nullable Duration queryTimeout, @Nullable Duration deadlineMargin) {

  /**
   * Returns the settings the module uses: {@code stratum.data.*} with the defaults for anything
   * unset, changed by every {@link DataPropertiesCustomizer} in {@code META-INF/spring.factories},
   * then validated.
   *
   * @param environment the application's environment
   * @return the settings
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-CORE-1003} if a setting breaks
   *     its rules
   */
  public static DataProperties resolve(Environment environment) {
    return StratumCustomizers.resolveSettings(
        environment, DataConstants.PREFIX, DataProperties.class, DataPropertiesCustomizer.class);
  }

  /**
   * Returns whether {@code query-timeout}, when set, is 0 or more. Validation reports a negative
   * value as {@code stratum.data.query-timeout-not-negative breaks @AssertTrue}.
   *
   * @return whether the time limit is not negative
   */
  @AssertTrue
  public boolean isQueryTimeoutNotNegative() {
    return queryTimeout == null || !queryTimeout.isNegative();
  }

  /**
   * Returns whether {@code deadline-margin}, when set, is 0 or more. Validation reports a negative
   * value as {@code stratum.data.deadline-margin-not-negative breaks @AssertTrue}.
   *
   * @return whether the margin is not negative
   */
  @AssertTrue
  public boolean isDeadlineMarginNotNegative() {
    return deadlineMargin == null || !deadlineMargin.isNegative();
  }
}
