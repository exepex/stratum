package com.exepex.stratum.web;

import com.exepex.stratum.core.condition.StratumModuleConstants;
import lombok.experimental.UtilityClass;

/** The property keys and error body members the web module uses (ST-ENG-45). */
@UtilityClass
public class WebConstants {

  /** The module's name, as in {@code stratum.web.enabled}. Both web starters are this module. */
  public static final String MODULE = "web";

  /** The prefix of the module's own settings. */
  public static final String PREFIX = StratumModuleConstants.PREFIX + "." + MODULE;

  /** The switch that turns the module on. */
  public static final String ENABLED = StratumModuleConstants.enabledProperty(MODULE);

  /** The setting that maps a service's error codes to HTTP statuses. */
  public static final String ERROR_STATUSES = PREFIX + ".error.statuses";

  /** The error body member that holds the error code, such as {@code SX-WEB-1001}. */
  public static final String CODE = "code";

  /** The error body member that holds the severity: {@code ERROR} or {@code WARNING}. */
  public static final String SEVERITY = "severity";

  /** The error body member that lists the problems with single fields of the request. */
  public static final String DETAILS = "details";
}
