package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.CARD;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.EMAIL;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.testsupport.LoggingTestData;
import java.util.function.Consumer;
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
          new RedactingJsonMembersCustomizer(LoggingTestData.environmentWithConsoleFormat("ecs"));
      JsonWriter<Object> writer = JsonWriter.of(members -> addEcsLogLine(members, customizer));
      return writer.writeToString(new Object());
    }

    /** Adds a safe field, a nested secret and an error with a stack trace, then customizes them. */
    private void addEcsLogLine(Members<Object> members, RedactingJsonMembersCustomizer customizer) {
      members.add("userAgent", "bot (+mailto:" + EMAIL + ")");
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

    /** The service's extra-keys property. */
    private static final String EXTRA_KEYS = LoggingConstants.PREFIX + ".redaction.extra-keys";

    /** A stack trace whose frame line is written as it is unless the whole field is masked. */
    private static final String STACK_TRACE =
        "java.lang.Exception: failed\n\tat com.example.Orders.place(Orders.java:42)";

    /** Writes a line holding only the stack trace field, with that field listed in extra-keys. */
    private String writeWithExtraKey(
        String format, String extraKey, Consumer<Members<Object>> addStackTrace) {
      var customizer =
          new RedactingJsonMembersCustomizer(
              LoggingTestData.environmentWithConsoleFormat(format)
                  .withProperty(EXTRA_KEYS, extraKey));
      JsonWriter<Object> writer =
          JsonWriter.of(members -> addThenCustomize(members, addStackTrace, customizer));
      return writer.writeToString(new Object());
    }

    /** Adds the stack trace field, then customizes the members. */
    private void addThenCustomize(
        Members<Object> members,
        Consumer<Members<Object>> addStackTrace,
        RedactingJsonMembersCustomizer customizer) {
      addStackTrace.accept(members);
      customizer.customize(members);
    }

    @Test
    @DisplayName("is masked in full under ECS")
    void ecsStackTraceIsMasked() {
      assertThat(
              writeWithExtraKey(
                  "ecs",
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
              writeWithExtraKey(
                  "logstash",
                  STACK_TRACE_FIELD,
                  members -> members.add(STACK_TRACE_FIELD, STACK_TRACE)))
          .contains("\"stack_trace\":\"****\"");
    }

    @Test
    @DisplayName("is masked in full under GELF, for the full message too")
    void gelfFullMessageIsMasked() {
      assertThat(
              writeWithExtraKey(
                  "gelf", "full_message", members -> members.add("full_message", STACK_TRACE)))
          .contains("\"full_message\":\"****\"");
    }
  }
}
