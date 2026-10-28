package com.exepex.stratum.logging.it;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.CARD;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.EMAIL;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.JWT;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    classes = OrdersApplication.class,
    properties = {
      "spring.application.name=orders",
      "stratum.environment=prod",
      "stratum.logging.enabled=true"
    })
// Closing the context resets logging, so later tests in this JVM start from Spring Boot's own.
@DirtiesContext
@Slf4j
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("A service with the logging starter switched on writes redacted ECS JSON")
class LoggingStarterIntegrationTest {

  @Test
  @DisplayName("each line is ECS JSON with the service's name and environment")
  void writesEcsWithServiceFields(CapturedOutput output) {
    log.info("order placed");

    assertThat(output)
        .contains("\"message\":\"order placed\"")
        .contains("\"service\":{\"name\":\"orders\"")
        .contains("\"environment\":\"prod\"");
  }

  @Test
  @DisplayName("secrets and personal data are masked in the message, MDC and key-value pairs")
  void masksEveryPartOfTheLine(CapturedOutput output) {
    MDC.put("authorization", "Bearer " + JWT);
    try {
      log.atInfo().addKeyValue("password", PASSWORD).log("card {} for {}", CARD, EMAIL);
    } finally {
      MDC.remove("authorization");
    }

    assertThat(output)
        .doesNotContain(PASSWORD)
        .doesNotContain(JWT)
        .doesNotContain(CARD)
        .doesNotContain(EMAIL)
        .contains("\"message\":\"card ****1111 for ****.com\"");
  }

  @Test
  @DisplayName(
      "values that are not text are masked by their toString, and lists element by element")
  void masksValuesThatAreNotText(CapturedOutput output) {
    var customer = new Customer("Jane", PASSWORD, EMAIL);

    log.atInfo()
        .addKeyValue("customer", customer)
        .addKeyValue("ref", Long.valueOf(CARD))
        .addKeyValue("notes", List.of("token=" + PASSWORD))
        .addKeyValue("count", Integer.valueOf("3"))
        .log("customer saved");

    assertThat(output)
        .contains("customer saved")
        .doesNotContain(PASSWORD)
        .doesNotContain(EMAIL)
        .doesNotContain(CARD)
        .contains("\"count\":3");
  }

  /**
   * A record whose generated {@code toString()} holds a secret and an email address.
   *
   * @param name the customer's name
   * @param dbPassword a secret
   * @param email the customer's email address
   */
  private record Customer(String name, String dbPassword, String email) {}

  @Test
  @DisplayName("a secret in an exception's message and stack trace is masked")
  void masksExceptions(CapturedOutput output) {
    var failure = new IllegalStateException("rejected password=" + PASSWORD);

    log.error("payment failed", failure);

    assertThat(output).contains("payment failed").doesNotContain(PASSWORD);
  }
}
