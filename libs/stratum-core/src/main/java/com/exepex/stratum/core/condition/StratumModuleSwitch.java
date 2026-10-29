package com.exepex.stratum.core.condition;

import lombok.experimental.UtilityClass;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/** Reads a module's on/off property, {@code stratum.<module>.enabled}, for every Stratum module. */
@UtilityClass
public class StratumModuleSwitch {

  /**
   * Returns whether a module is switched on. A module whose property is not set is off (ADR 0003).
   *
   * @param environment the application's environment
   * @param module the module's kebab-case name, such as {@code http-client}
   * @return whether {@code stratum.<module>.enabled} is {@code true}
   */
  public static boolean isEnabled(Environment environment, String module) {
    // Spring's Boolean return is nullable for SpotBugs, so the comparison avoids unboxing it.
    return Boolean.TRUE.equals(
        Binder.get(environment)
            .bind(StratumModuleConstants.enabledProperty(module), Boolean.class)
            .orElse(false));
  }
}
