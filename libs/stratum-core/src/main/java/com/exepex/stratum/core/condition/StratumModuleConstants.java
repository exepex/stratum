package com.exepex.stratum.core.condition;

import lombok.experimental.UtilityClass;

/** The parts of every module's on/off property, {@code stratum.<module>.enabled} (ST-ENG-45). */
@UtilityClass
public class StratumModuleConstants {

  /** The first part of every Stratum property. */
  public static final String PREFIX = "stratum";

  /** The last part of a module's on/off property. */
  public static final String ENABLED = "enabled";

  /**
   * Returns the on/off property of a module.
   *
   * @param module the module's kebab-case name, such as {@code http-client}
   * @return the property, such as {@code stratum.http-client.enabled}
   */
  public static String enabledProperty(String module) {
    return PREFIX + "." + module + "." + ENABLED;
  }
}
