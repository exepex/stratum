package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.CARD;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.testsupport.LoggingTestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("RedactingJsonMembersCustomizer masks every value of a JSON log line")
class RedactingJsonMembersCustomizerTest {

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
    assertThat(writeLogLine(new MockEnvironment().withProperty(LoggingConstants.ENABLED, "false")))
        .contains(PASSWORD);
  }
}
