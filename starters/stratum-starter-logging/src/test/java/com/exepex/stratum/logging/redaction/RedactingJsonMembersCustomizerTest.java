package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.CARD;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.EMAIL;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.testsupport.LoggingTestData;
import com.exepex.stratum.logging.testsupport.SpringBootJsonLines;
import com.exepex.stratum.logging.testsupport.ValueTextTestData;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("RedactingJsonMembersCustomizer masks every value of a JSON log line")
class RedactingJsonMembersCustomizerTest {

  /** The stack trace field of ECS and Logstash. */
  private static final String STACK_TRACE_FIELD = "stack_trace";

  /** Spring Boot's name for GELF. */
  private static final String GELF = "gelf";

  /** Spring Boot's name for ECS. */
  private static final String ECS = "ecs";

  /** The web module's user agent field in ECS and Logstash. */
  private static final String USER_AGENT_FIELD = "userAgent";

  /** The service's extra-keys property. */
  private static final String EXTRA_KEYS = LoggingConstants.PREFIX + ".redaction.extra-keys";

  /** The service's extra-patterns property. */
  private static final String EXTRA_PATTERNS =
      LoggingConstants.PREFIX + ".redaction.extra-patterns";

  /** Writes a line holding only the given fields, with one of the service's own rules set. */
  private String writeWithServiceRule(
      String format, String property, String rule, Consumer<Members<Object>> addFields) {
    var customizer =
        new RedactingJsonMembersCustomizer(
            LoggingTestData.environmentWithConsoleFormat(format).withProperty(property, rule));
    JsonWriter<Object> writer =
        JsonWriter.of(members -> addThenCustomize(members, addFields, customizer));
    return writer.writeToString(new Object());
  }

  /** Adds the fields, then customizes the members. */
  private void addThenCustomize(
      Members<Object> members,
      Consumer<Members<Object>> addFields,
      RedactingJsonMembersCustomizer customizer) {
    addFields.accept(members);
    customizer.customize(members);
  }

  /** The log line's message, which holds a card number. */
  private static final String MESSAGE = "paid with " + CARD;

  /** The log line's ordinary field. */
  private static final String SERVICE = "orders";

  /** Writes the log line through a JSON writer that the customizer has customized. */
  private String writeLogLine(MockEnvironment environment) {
    var customizer = new RedactingJsonMembersCustomizer(environment);
    JsonWriter<Object> writer = JsonWriter.of(members -> addLogLine(members, customizer));
    // The members hold fixed values, so the object written is never read.
    return writer.writeToString(new Object());
  }

  /** Adds the log line's fields, a message, a secret and an ordinary one, then customizes them. */
  private void addLogLine(Members<Object> members, RedactingJsonMembersCustomizer customizer) {
    members.add("message", MESSAGE);
    members.add("password", PASSWORD);
    members.add("service", SERVICE);
    customizer.customize(members);
  }

  @Nested
  @DisplayName("the module switch")
  class ModuleSwitch {

    @Test
    @DisplayName("with the module on, the message and secret field are masked and the rest is kept")
    void masksWhenModuleIsOn() {
      var json = writeLogLine(LoggingTestData.environmentWithModuleOn());

      assertThat(json)
          .doesNotContain(CARD)
          .doesNotContain(PASSWORD)
          .contains("\"message\":\"paid with ****1111\"")
          .contains("\"password\":\"****\"")
          .contains("\"service\":\"orders\"");
    }

    @Test
    @DisplayName("with the module unset or off, the line is written unchanged")
    void leavesLineAloneWhenModuleIsOff() {
      assertThat(writeLogLine(new MockEnvironment())).contains(CARD).contains(PASSWORD);
      assertThat(
              writeLogLine(new MockEnvironment().withProperty(LoggingConstants.ENABLED, "false")))
          .contains(PASSWORD);
    }
  }

  @Nested
  @DisplayName("the kind of each field")
  class FieldKinds {

    /** A stack trace whose message holds an email address, and one frame line. */
    private static final String STACK_TRACE =
        "java.lang.Exception: " + EMAIL + "\n\tat com.example.Login.check(Password.java:12)";

    /** Writes an ECS log line with a safe field, a nested secret and a stack trace. */
    private String writeEcsLogLine() {
      var customizer =
          new RedactingJsonMembersCustomizer(LoggingTestData.environmentWithConsoleFormat(ECS));
      JsonWriter<Object> writer = JsonWriter.of(members -> addEcsLogLine(members, customizer));
      return writer.writeToString(new Object());
    }

    /** Adds a safe field, a nested secret and an error with a stack trace, then customizes them. */
    private void addEcsLogLine(Members<Object> members, RedactingJsonMembersCustomizer customizer) {
      members.add(USER_AGENT_FIELD, "bot (+mailto:" + EMAIL + ")");
      members.add("password").usingMembers(nested -> nested.add("new", PASSWORD));
      members.add("error").usingMembers(error -> error.add(STACK_TRACE_FIELD, STACK_TRACE));
      customizer.customize(members);
    }

    @Test
    @DisplayName("a safe field is written as it is, even when it looks personal")
    void safeFieldIsKept() {
      assertThat(writeEcsLogLine()).contains("\"userAgent\":\"bot (+mailto:" + EMAIL + ")\"");
    }

    @Test
    @DisplayName("a nested field is matched by the words of its whole path")
    void nestedSecretIsMasked() {
      assertThat(writeEcsLogLine()).doesNotContain(PASSWORD).contains("\"new\":\"****\"");
    }

    @Test
    @DisplayName("a stack trace keeps its frame lines and has its message masked")
    void stackTraceIsMaskedExceptFrames() {
      assertThat(writeEcsLogLine())
          .contains("java.lang.Exception: ****.com")
          .contains("\\tat com.example.Login.check(Password.java:12)");
    }
  }

  @Nested
  @DisplayName("a stack trace field the service names in extra-keys")
  class StackTraceInExtraKeys {

    /** A stack trace whose frame line is written as it is unless the whole field is masked. */
    private static final String STACK_TRACE =
        "java.lang.Exception: failed\n\tat com.example.Orders.place(Orders.java:42)";

    @Test
    @DisplayName("is masked in full under ECS")
    void ecsStackTraceIsMasked() {
      assertThat(
              writeWithServiceRule(
                  ECS,
                  EXTRA_KEYS,
                  STACK_TRACE_FIELD,
                  members ->
                      members
                          .add("error")
                          .usingMembers(error -> error.add(STACK_TRACE_FIELD, STACK_TRACE))))
          .contains("\"stack_trace\":\"****\"");
    }

    @Test
    @DisplayName("is masked in full under Logstash")
    void logstashStackTraceIsMasked() {
      assertThat(
              writeWithServiceRule(
                  "logstash",
                  EXTRA_KEYS,
                  STACK_TRACE_FIELD,
                  members -> members.add(STACK_TRACE_FIELD, STACK_TRACE)))
          .contains("\"stack_trace\":\"****\"");
    }

    @Test
    @DisplayName("is masked in full under GELF, for the full message too")
    void gelfFullMessageIsMasked() {
      assertThat(
              writeWithServiceRule(
                  GELF,
                  EXTRA_KEYS,
                  "full_message",
                  members -> members.add("full_message", STACK_TRACE)))
          .contains("\"full_message\":\"****\"");
    }
  }

  @Nested
  @DisplayName("a safe field the service names in its own rules")
  class SafeFieldInServiceRules {

    /** A user agent holding an employee ID that the service's pattern masks. */
    private static final String USER_AGENT = "orders-app/1.0 (EMP-123456)";

    /** Logback's number for the INFO level, as Logstash writes it. */
    private static final int INFO_LEVEL_VALUE = 20_000;

    @Test
    @DisplayName("is masked in full by an extra key, in ECS, Logstash and GELF")
    void extraKeyMasksSafeField() {
      assertThat(
              writeWithServiceRule(
                  ECS,
                  EXTRA_KEYS,
                  USER_AGENT_FIELD,
                  members -> members.add(USER_AGENT_FIELD, USER_AGENT)))
          .contains("\"userAgent\":\"****\"");
      assertThat(
              writeWithServiceRule(
                  "logstash",
                  EXTRA_KEYS,
                  USER_AGENT_FIELD,
                  members -> members.add(USER_AGENT_FIELD, USER_AGENT)))
          .contains("\"userAgent\":\"****\"");
      assertThat(
              writeWithServiceRule(
                  GELF,
                  EXTRA_KEYS,
                  USER_AGENT_FIELD,
                  members -> members.add("_userAgent", USER_AGENT)))
          .contains("\"_userAgent\":\"****\"");
    }

    @Test
    @DisplayName("has an extra pattern's matches masked, and the rest kept")
    void extraPatternMasksInsideSafeField() {
      assertThat(
              writeWithServiceRule(
                  ECS,
                  EXTRA_PATTERNS,
                  "EMP-\\d{6}",
                  members -> members.add(USER_AGENT_FIELD, USER_AGENT)))
          .contains("\"userAgent\":\"orders-app/1.0 (****)\"");
    }

    @Test
    @DisplayName("is masked by an extra key even when its value is not text")
    void extraKeyMasksNonTextSafeField() {
      assertThat(
              writeWithServiceRule(
                  "logstash",
                  EXTRA_KEYS,
                  "level_value",
                  members -> members.add("level_value", INFO_LEVEL_VALUE)))
          .contains("\"level_value\":\"****\"");
    }
  }

  @Nested
  @DisplayName("Spring Boot's own JSON and a value that cannot be read")
  class BootJsonAndUnreadableValues {

    /** GELF on the console, with the service name GELF needs as its host. */
    private final MockEnvironment gelf =
        LoggingTestData.environmentWithConsoleFormat(GELF)
            .withProperty("spring.application.name", SERVICE);

    @Test
    @DisplayName("GELF's timestamp, a safe field, stays a JSON number with Logback and Log4j2")
    void gelfTimestampStaysNumber() throws IOException {
      var numericTimestamp = Pattern.compile("\"timestamp\":\\d+\\.\\d+[,}]");

      assertThat(SpringBootJsonLines.logbackLine(GELF, gelf, MESSAGE))
          .containsPattern(numericTimestamp)
          .doesNotContain(CARD);
      assertThat(SpringBootJsonLines.log4j2Line(GELF, gelf, MESSAGE))
          .containsPattern(numericTimestamp)
          .doesNotContain(CARD);
    }

    @Test
    @DisplayName("a value whose toString() throws NullPointerException is replaced; the rest stays")
    void unreadableValueKeepsTheLine() {
      var broken = ValueTextTestData.throwing(new NullPointerException());
      var line =
          writeWithServiceRule(
              ECS, EXTRA_KEYS, "badge-no", members -> addBrokenValue(members, broken));

      assertThat(line)
          .isEqualTo("{\"order\":\"[REDACTION_FAILED]\",\"service\":\"" + SERVICE + "\"}");
    }

    /** Adds the broken value and an ordinary field. */
    private void addBrokenValue(Members<Object> members, Object broken) {
      members.add("order", broken);
      members.add("service", SERVICE);
    }
  }
}
