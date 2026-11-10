package com.exepex.stratum.restclient.it.redaction;

import static com.exepex.stratum.restclient.testsupport.calls.CallLines.assertCallLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.restclient.testsupport.QueuedDownstream;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.calls.CallsApplication;
import com.exepex.stratum.restclient.testsupport.calls.CallsTestData;
import com.exepex.stratum.restclient.testsupport.calls.OrdersCalls;
import java.util.function.Supplier;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClientException;

@SpringBootTest(
    classes = CallsApplication.class,
    properties = {
      "stratum.logging.enabled=true",
      "stratum.logging.format=text",
      "spring.application.name=orders-service"
    })
@TestPropertySource(locations = CallsTestData.SETTINGS)
// Closing the context resets logging, so the next test class starts from Spring Boot's own.
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
@Timeout(30)
@DisplayName("In the logging starter's text format, the line is the message, then key=value fields")
class CallLogTextFormatIntegrationTest {

  /** The other service, which answers each request with the next queued status. */
  @AutoClose private static final QueuedDownstream DOWNSTREAM = QueuedDownstream.start();

  @Autowired private OrdersCalls orders;

  /**
   * Points the clients at the other service, and the ones these tests do not call at a closed port.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    Supplier<Object> unused = () -> RestClientTestData.UNUSED_URL;
    CallsTestData.addresses(registry, DOWNSTREAM::url, unused, unused);
  }

  @Test
  @DisplayName("a failed call writes the fixed message, then its fields, the url masked")
  void textFormat(CapturedOutput output) {
    CallsTestData.failEveryAttempt(DOWNSTREAM);

    assertThatExceptionOfType(RestClientException.class)
        .isThrownBy(() -> orders.find(CallsTestData.ORDER_ID, CallsTestData.CARD));

    var line =
        assertCallLine(
            output,
            CallsTestData.ORDERS,
            "WARN",
            ": HTTP client call failed ",
            "client=orders",
            "uri=/orders/{id}?{queryParam-status}",
            "url=http://127.0.0.1:",
            "/orders/8812?status=****1111",
            "status=503",
            "outcome=SERVER_ERROR",
            "attempts=4");
    assertThat(line).doesNotContain("call failed:").doesNotContain(CallsTestData.CARD);
  }
}
