package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.environmentWithConsoleFormat;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.testsupport.LoggingTestData;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("KnownLogFields knows which fields of each format are safe or stack traces")
class KnownLogFieldsTest {

  /** Spring Boot's time field, written by ECS and Logstash. */
  private static final String TIMESTAMP = "@timestamp";

  /** ECS's level field. */
  private static final String ECS_LEVEL = "log.level";

  /** The web module's trace ID field. */
  private static final String TRACE_ID = "traceId";

  /** The level field of Logstash and GELF. */
  private static final String LEVEL = "level";

  /** Spring Boot's name for ECS. */
  private static final String ECS = "ecs";

  /** ECS's stack trace field. */
  private static final String ECS_STACK_TRACE = "error.stack_trace";

  /** Returns the known fields for a console format and a different file format. */
  private KnownLogFields fieldsFor(String consoleFormat, String fileFormat) {
    return KnownLogFields.from(
        environmentWithConsoleFormat(consoleFormat)
            .withProperty(LoggingConstants.FILE_FORMAT, fileFormat));
  }

  @Nested
  @DisplayName("with ECS")
  class Ecs {

    /** Fields that Spring Boot, Stratum or the web module fill with no personal data. */
    private static final List<String> SAFE_PATHS =
        List.of(
            TIMESTAMP,
            ECS_LEVEL,
            "log.logger",
            "process.thread.name",
            "service.name",
            "service.node.name",
            "ecs.version",
            "error.type",
            "error.code",
            "instanceId",
            TRACE_ID,
            "httpRoute",
            "userAgent",
            "browserVersion");

    /** Fields that may hold personal data, or only look like safe ones. */
    private static final List<String> SEARCHED_PATHS =
        List.of(
            "message",
            "error.message",
            "service.contact",
            "service",
            "log",
            LEVEL,
            "host",
            "version",
            "_traceId",
            "httpPath",
            "httpQuery",
            "correlationId",
            "clientIp",
            "clientCity",
            "workflowId");

    private final KnownLogFields fields = KnownLogFields.from(environmentWithConsoleFormat(ECS));

    @ParameterizedTest
    @FieldSource("SAFE_PATHS")
    @DisplayName("a field that never holds personal data is safe")
    void safePathIsSafe(String path) {
      assertThat(fields.isSafe(path)).isTrue();
    }

    @ParameterizedTest
    @FieldSource("SEARCHED_PATHS")
    @DisplayName("any other field, matched by its whole path, is searched")
    void otherPathIsSearched(String path) {
      assertThat(fields.isSafe(path)).isFalse();
    }

    @Test
    @DisplayName("the error's stack trace is a stack trace")
    void stackTraceIsKnown() {
      assertThat(fields.isStackTrace(ECS_STACK_TRACE)).isTrue();
      assertThat(fields.isStackTrace("stack_trace")).isFalse();
      assertThat(fields.isStackTrace("message")).isFalse();
    }
  }

  @Nested
  @DisplayName("with Logstash")
  class Logstash {

    private final KnownLogFields fields =
        KnownLogFields.from(environmentWithConsoleFormat("logstash"));

    @Test
    @DisplayName("its own fields and the web fields are safe, ECS's are not")
    void logstashFieldsAreSafe() {
      assertThat(fields.isSafe(LEVEL)).isTrue();
      assertThat(fields.isSafe("thread_name")).isTrue();
      assertThat(fields.isSafe("instanceId")).isTrue();
      assertThat(fields.isSafe(TRACE_ID)).isTrue();
      assertThat(fields.isSafe(ECS_LEVEL)).isFalse();
      assertThat(fields.isSafe("message")).isFalse();
    }

    @Test
    @DisplayName("the stack trace field is a stack trace")
    void stackTraceIsKnown() {
      assertThat(fields.isStackTrace("stack_trace")).isTrue();
      assertThat(fields.isStackTrace(ECS_STACK_TRACE)).isFalse();
    }
  }

  @Nested
  @DisplayName("with GELF")
  class Gelf {

    private final KnownLogFields fields = KnownLogFields.from(environmentWithConsoleFormat("gelf"));

    @Test
    @DisplayName("its own fields and the web fields with an underscore are safe")
    void gelfFieldsAreSafe() {
      assertThat(fields.isSafe("host")).isTrue();
      assertThat(fields.isSafe("_level_name")).isTrue();
      assertThat(fields.isSafe("_error_code")).isTrue();
      assertThat(fields.isSafe("_instanceId")).isTrue();
      assertThat(fields.isSafe("instanceId")).isFalse();
      assertThat(fields.isSafe("_traceId")).isTrue();
      assertThat(fields.isSafe(TRACE_ID)).isFalse();
      assertThat(fields.isSafe("short_message")).isFalse();
    }

    @Test
    @DisplayName("the stack trace and the full message, which ends with it, are stack traces")
    void stackTracesAreKnown() {
      assertThat(fields.isStackTrace("_error_stack_trace")).isTrue();
      assertThat(fields.isStackTrace("full_message")).isTrue();
    }
  }

  @Nested
  @DisplayName("when the format is not one of Spring Boot's, or the outputs differ")
  class UnclearFormat {

    @Test
    @DisplayName("a different console and file format keep only the fields safe in both")
    void differentFormatsKeepCommonFields() {
      var ecsAndLogstash = fieldsFor(ECS, "logstash");

      assertThat(ecsAndLogstash.isSafe(TIMESTAMP)).isTrue();
      assertThat(ecsAndLogstash.isSafe(TRACE_ID)).isTrue();
      assertThat(ecsAndLogstash.isSafe(LEVEL)).isFalse();
      assertThat(ecsAndLogstash.isSafe(ECS_LEVEL)).isFalse();
      assertThat(ecsAndLogstash.isStackTrace("stack_trace")).isFalse();
    }

    @Test
    @DisplayName("ECS on the console and GELF in the file share no field, so all are searched")
    void ecsAndGelfShareNothing() {
      var ecsAndGelf = fieldsFor(ECS, "gelf");

      assertThat(ecsAndGelf.isSafe("host")).isFalse();
      assertThat(ecsAndGelf.isSafe("version")).isFalse();
      assertThat(ecsAndGelf.isSafe(LEVEL)).isFalse();
      assertThat(ecsAndGelf.isSafe(TIMESTAMP)).isFalse();
      assertThat(ecsAndGelf.isStackTrace(ECS_STACK_TRACE)).isFalse();
      assertThat(ecsAndGelf.isStackTrace("full_message")).isFalse();
    }

    @Test
    @DisplayName("a format's name is matched ignoring case")
    void formatNameIgnoresCase() {
      assertThat(KnownLogFields.from(environmentWithConsoleFormat("ECS")).isSafe(ECS_LEVEL))
          .isTrue();
    }

    @Test
    @DisplayName("a service's own formatter class, or no format, makes every field searched")
    void unknownFormatKnowsNothing() {
      var ownFormatter = fieldsFor(ECS, "com.example.MyFormatter");
      var noFormat = KnownLogFields.from(LoggingTestData.environmentWithModuleOn());

      assertThat(ownFormatter.isSafe(TIMESTAMP)).isFalse();
      assertThat(ownFormatter.isStackTrace(ECS_STACK_TRACE)).isFalse();
      assertThat(noFormat.isSafe(TIMESTAMP)).isFalse();
    }
  }
}
