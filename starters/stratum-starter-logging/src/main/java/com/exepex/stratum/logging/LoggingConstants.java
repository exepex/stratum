package com.exepex.stratum.logging;

import com.exepex.stratum.core.condition.StratumModuleConstants;
import java.util.Set;
import lombok.experimental.UtilityClass;

/** The property keys, log fields and metric names the logging module uses (ST-ENG-45). */
@UtilityClass
public class LoggingConstants {

  /** The module's name, as in {@code stratum.logging.enabled}. */
  public static final String MODULE = "logging";

  /** The prefix of the module's own settings. */
  public static final String PREFIX = StratumModuleConstants.PREFIX + "." + MODULE;

  /** The switch that turns the module on. */
  public static final String ENABLED = StratumModuleConstants.enabledProperty(MODULE);

  /** The module's log format, such as {@code ecs} or {@code text}. */
  public static final String FORMAT = PREFIX + ".format";

  /** The service's own regular expressions for values to mask. */
  public static final String EXTRA_PATTERNS = PREFIX + ".redaction.extra-patterns";

  /** The service's name, written as {@code service.name} on every line. */
  public static final String APPLICATION_NAME = "spring.application.name";

  /** Spring Boot's structured format for console logs. */
  public static final String CONSOLE_FORMAT = "logging.structured.format.console";

  /** Spring Boot's structured format for the log file, when {@code logging.file.name} is set. */
  public static final String FILE_FORMAT = "logging.structured.format.file";

  /** Spring Boot's ECS field {@code service.environment}. */
  public static final String ECS_ENVIRONMENT = "logging.structured.ecs.service.environment";

  /** The longest stack trace Spring Boot writes, in characters. */
  public static final String STACK_TRACE_MAX_LENGTH =
      "logging.structured.json.stacktrace.max-length";

  /** Whether Spring Boot writes the root cause of a stack trace first or last. */
  public static final String STACK_TRACE_ROOT = "logging.structured.json.stacktrace.root";

  /** The log field, and metric tag, with the name of the workflow a line belongs to. */
  public static final String WORKFLOW = "workflow";

  /** The log field, and metric tag, with the name of the outermost workflow. */
  public static final String ROOT_WORKFLOW = "root_workflow";

  /** The log field, and span attribute, with the ID the service gave its workflow. */
  public static final String WORKFLOW_ID = "workflow_id";

  /** The name of the timer and span of every workflow. */
  public static final String WORKFLOW_OBSERVATION = "stratum.workflow";

  /** The profiles in which plain-text logs are expected. */
  public static final Set<String> LOCAL_PROFILES = Set.of("local", "test");
}
