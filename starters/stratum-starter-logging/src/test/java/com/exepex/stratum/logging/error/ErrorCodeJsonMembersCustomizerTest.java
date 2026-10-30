package com.exepex.stratum.logging.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.LoggingErrorCode;
import com.exepex.stratum.logging.testsupport.LoggingTestData;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.mock.env.MockEnvironment;

@DisplayName(
    "ErrorCodeJsonMembersCustomizer writes a logged exception's error code in its own field")
class ErrorCodeJsonMembersCustomizerTest {

  /** A failure with a code, as Stratum or a service throws it. */
  private static final StratumException CODED_FAILURE =
      new StratumException(LoggingErrorCode.APPLICATION_NAME_MISSING);

  /** The log line's message. */
  private static final String MESSAGE = "order failed";

  /** The log line without an error code field. */
  private static final String LINE_WITHOUT_CODE = "{\"message\":\"order failed\"}";

  /** The code of {@link #CODED_FAILURE}. */
  private static final String CODE = "SX-LOGGING-1001";

  /** The environment with the module on, after it has set the default ECS format. */
  private final MockEnvironment moduleOn =
      LoggingTestData.environmentWithModuleOn()
          .withProperty(LoggingConstants.CONSOLE_FORMAT, "ecs");

  /** Writes a log line with the exception through a writer the customizer has customized. */
  private String writeLogLine(MockEnvironment environment, @Nullable Throwable exception) {
    var customizer = new ErrorCodeJsonMembersCustomizer(environment);
    var logger = new LoggerContext().getLogger("orders");
    var event = new LoggingEvent(null, logger, Level.ERROR, MESSAGE, exception, null);
    return JsonWriter.<Object>of(members -> addLogLine(members, customizer)).writeToString(event);
  }

  /** Adds the log line's message, then lets the customizer add its field. */
  private void addLogLine(Members<Object> members, ErrorCodeJsonMembersCustomizer customizer) {
    members.add("message", MESSAGE);
    customizer.customize(members);
  }

  @Test
  @DisplayName("a logged StratumException's code is written as error.code")
  void writesCodeOfLoggedException() {
    assertThat(writeLogLine(moduleOn, CODED_FAILURE))
        .isEqualTo("{\"message\":\"order failed\",\"error.code\":\"" + CODE + "\"}");
  }

  @Test
  @DisplayName("the code of a StratumException among the causes is written too")
  void writesCodeOfCause() {
    var wrapped = new IllegalStateException(MESSAGE, CODED_FAILURE);

    assertThat(writeLogLine(moduleOn, wrapped)).contains(CODE);
  }

  @Test
  @DisplayName("a line with no exception, or one without a code, has no error code field")
  void writesNothingWithoutCode() {
    assertThat(writeLogLine(moduleOn, null)).isEqualTo(LINE_WITHOUT_CODE);
    assertThat(writeLogLine(moduleOn, new IllegalStateException(MESSAGE)))
        .isEqualTo(LINE_WITHOUT_CODE);
  }

  @Test
  @DisplayName("an event that is not Logback's, such as Log4j2's, gets no error code field")
  void ignoresOtherLoggingBackends() {
    var customizer = new ErrorCodeJsonMembersCustomizer(moduleOn);

    assertThat(
            JsonWriter.<Object>of(members -> addLogLine(members, customizer))
                .writeToString(CODED_FAILURE))
        .isEqualTo(LINE_WITHOUT_CODE);
  }

  @Test
  @DisplayName(
      "when the service sets GELF, the field is named _error_code, as GELF's own fields are")
  void usesGelfFieldName() {
    moduleOn.setProperty(LoggingConstants.CONSOLE_FORMAT, "GELF");

    assertThat(writeLogLine(moduleOn, CODED_FAILURE))
        .endsWith(",\"_error_code\":\"" + CODE + "\"}");
  }

  @Test
  @DisplayName("with a plain-text console and a JSON file, the file's lines get the field")
  void writesCodeForStructuredFile() {
    var textConsoleJsonFile =
        LoggingTestData.environmentWithModuleOn()
            .withProperty(LoggingConstants.FORMAT, "text")
            .withProperty(LoggingConstants.FILE_FORMAT, "ecs");

    assertThat(writeLogLine(textConsoleJsonFile, CODED_FAILURE)).contains(CODE);
  }

  @Test
  @DisplayName("with the module unset or off, nothing is added")
  void addsNothingWhenModuleIsOff() {
    assertThat(writeLogLine(new MockEnvironment(), CODED_FAILURE)).isEqualTo(LINE_WITHOUT_CODE);
    assertThat(
            writeLogLine(
                new MockEnvironment().withProperty(LoggingConstants.ENABLED, "false"),
                CODED_FAILURE))
        .isEqualTo(LINE_WITHOUT_CODE);
  }
}
