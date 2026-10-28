package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.CARD;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.LoggingConstants;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("RedactingJsonMembersCustomizer masks every value of a JSON log line")
class RedactingJsonMembersCustomizerTest {

  /** A log line with a card in its message, a secret field and an ordinary field. */
  private static final Map<String, String> LINE =
      Map.of("message", "paid with " + CARD, "password", PASSWORD, "service", "orders");

  /** Writes the line through a JSON writer that the customizer has customized. */
  @SuppressWarnings("unchecked")
  private static String write(MockEnvironment environment) {
    var customizer = new RedactingJsonMembersCustomizer(environment);
    JsonWriter<Map<String, String>> writer =
        JsonWriter.of(
            members -> {
              members.add("message", line -> line.get("message"));
              members.add("password", line -> line.get("password"));
              members.add("service", line -> line.get("service"));
              customizer.customize((Members<Object>) (Members<?>) members);
            });
    return writer.writeToString(LINE);
  }

  @Test
  @DisplayName("with the module on, the message and secret field are masked and the rest is kept")
  void masksWhenModuleIsOn() {
    var json = write(new MockEnvironment().withProperty(LoggingConstants.ENABLED, "true"));

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
    assertThat(write(new MockEnvironment())).contains(CARD).contains(PASSWORD);
    assertThat(write(new MockEnvironment().withProperty(LoggingConstants.ENABLED, "false")))
        .contains(PASSWORD);
  }
}
