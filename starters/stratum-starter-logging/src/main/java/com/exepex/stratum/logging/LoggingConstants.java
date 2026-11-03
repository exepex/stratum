package com.exepex.stratum.logging;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import java.util.List;
import lombok.experimental.UtilityClass;

/** The property keys, log fields and metric names the logging module uses (ST-ENG-45). */
@StratumInternal
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

  /** Spring Boot's key for a logging configuration file of the service's own. */
  public static final String LOGGING_CONFIG = "logging.config";

  /**
   * The Logback configuration files Spring Boot loads on its own when they are on the class path.
   */
  public static final List<String> LOGBACK_CONFIGURATION_FILES =
      List.of(
          "logback-test.groovy",
          "logback-test.xml",
          "logback.groovy",
          "logback.xml",
          "logback-test-spring.groovy",
          "logback-test-spring.xml",
          "logback-spring.groovy",
          "logback-spring.xml");

  /** Logback's log event, present when the service logs with Logback. */
  public static final String LOGBACK_EVENT = "ch.qos.logback.classic.spi.ILoggingEvent";

  /** Log4j2's log event, present when the service logs with Log4j2. */
  public static final String LOG4J2_EVENT = "org.apache.logging.log4j.core.LogEvent";

  /** Spring Boot's ECS field {@code service.environment}. */
  public static final String ECS_ENVIRONMENT = "logging.structured.ecs.service.environment";

  /** Spring Boot's ECS field {@code service.node.name}, which holds the instance ID. */
  public static final String ECS_NODE_NAME = "logging.structured.ecs.service.node-name";

  /** Spring Boot's map of fields added to every JSON log line, by field name. */
  public static final String JSON_ADDED_FIELDS = "logging.structured.json.add";

  /** The longest stack trace Spring Boot writes, in characters. */
  public static final String STACK_TRACE_MAX_LENGTH =
      "logging.structured.json.stacktrace.max-length";

  /**
   * How many characters Spring Boot may print beyond max-field-length, so that Stratum's cut, which
   * never leaves half a value, ends a long stack trace.
   */
  public static final int STACK_TRACE_PRINT_MARGIN = 1024;

  /** Whether Spring Boot writes the root cause of a stack trace first or last. */
  public static final String STACK_TRACE_ROOT = "logging.structured.json.stacktrace.root";

  /**
   * Spring Boot's value of {@link #STACK_TRACE_ROOT} that starts a stack trace with its root cause.
   */
  public static final String ROOT_CAUSE_FIRST = "first";

  /** The log field with the name of the workflow a line belongs to. */
  public static final String WORKFLOW = "workflow";

  /** The log field with the name of the outermost workflow. */
  public static final String ROOT_WORKFLOW = "rootWorkflow";

  /** The log field with the ID the service gave its workflow. */
  public static final String WORKFLOW_ID = "workflowId";

  // The metric tags and span attributes form one dotted group, while the log fields have no dot:
  // Elasticsearch turns a dotted log field into a nested object, and OpenTelemetry forbids a name
  // that is also a group, such as "workflow" next to "workflow.id" on one span.

  /**
   * The prefix of the workflow's observation keys (metric tags and span attributes), not log keys.
   */
  public static final String WORKFLOW_KEY_PREFIX = "workflow.";

  /** The metric tag and span attribute with the workflow's name. */
  public static final String WORKFLOW_NAME_KEY = WORKFLOW_KEY_PREFIX + "name";

  /** The metric tag and span attribute with the name of the outermost workflow. */
  public static final String ROOT_WORKFLOW_KEY = WORKFLOW_KEY_PREFIX + "root";

  /** The span attribute with the ID the service gave its workflow. */
  public static final String WORKFLOW_ID_KEY = WORKFLOW_KEY_PREFIX + "id";

  /** The log field with the code of a logged {@code StratumException}. */
  public static final String ERROR_CODE = "error.code";

  /** The same field in GELF, whose own fields start with {@code _}. */
  public static final String GELF_ERROR_CODE = "_error_code";

  /**
   * The log field with the instance ID, in Logstash; ECS writes it as {@code service.node.name}.
   */
  public static final String INSTANCE_ID = "instanceId";

  /** The same field in GELF, whose own fields start with {@code _}. */
  public static final String GELF_INSTANCE_ID = "_instanceId";

  /** The name of the timer and span of every workflow. */
  public static final String WORKFLOW_OBSERVATION = "stratum.workflow";

  /** The key under which executors and Reactor carry the workflow fields to other threads. */
  public static final String WORKFLOW_FIELDS = "stratum.workflow.fields";

  /** The key under which executors and Reactor carry the MDC fields the service put itself. */
  public static final String SERVICE_MDC_FIELDS = "stratum.service.mdc.fields";
}
