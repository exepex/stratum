package com.exepex.stratum.core.properties;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import lombok.experimental.UtilityClass;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Reads {@code stratum.backward-compatible}, the one switch with which every module keeps the
 * service's external contracts as they were before Stratum (ADR 0010).
 */
@StratumInternal
@UtilityClass
public class BackwardCompatibleMode {

  /**
   * Returns whether backward-compatible mode is on. It is off when the property is not set.
   *
   * @param environment the application's environment
   * @return whether {@code stratum.backward-compatible} is {@code true}
   */
  public static boolean isOn(Environment environment) {
    // Spring's Boolean return is nullable for SpotBugs, so the comparison avoids unboxing it.
    return Boolean.TRUE.equals(
        Binder.get(environment)
            .bind(StratumModuleConstants.BACKWARD_COMPATIBLE, Boolean.class)
            .orElse(false));
  }
}
