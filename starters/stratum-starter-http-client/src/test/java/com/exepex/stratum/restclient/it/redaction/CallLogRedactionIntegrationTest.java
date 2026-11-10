package com.exepex.stratum.restclient.it.redaction;

import static com.exepex.stratum.restclient.testsupport.calls.CallLines.assertCallLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.restclient.StratumHttpClients;
import com.exepex.stratum.restclient.testsupport.QueuedDownstream;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.calls.CallsApplication;
import com.exepex.stratum.restclient.testsupport.calls.CallsTestData;
import java.net.URI;
import java.util.function.Supplier;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
    properties = {"stratum.logging.enabled=true", "spring.application.name=orders-service"})
@TestPropertySource(locations = CallsTestData.SETTINGS)
// Closing the context resets logging, so the text-format test starts from Spring Boot's own.
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
@Timeout(30)
@DisplayName("With the logging starter's redaction, a failed call's line carries the masked url")
class CallLogRedactionIntegrationTest {

  /** The most a line holding a url cut at the logging starter's 16 KB can take, 20 KB. */
  private static final int LINE_LIMIT = 20 * 1024;

  /** The other service, which answers each request with the next queued status. */
  @AutoClose private static final QueuedDownstream DOWNSTREAM = QueuedDownstream.start();

  @Autowired private StratumHttpClients clients;

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

  /** Makes a call of the orders client to the template, whose every attempt gets 503. */
  private void callFailing(String uriTemplate, Object... values) {
    CallsTestData.failEveryAttempt(DOWNSTREAM);
    var answer = clients.restClient(CallsTestData.ORDERS).get().uri(uriTemplate, values).retrieve();
    assertThatExceptionOfType(RestClientException.class).isThrownBy(answer::toBodilessEntity);
  }

  /** Makes a call of the orders client to the address as written, whose every attempt gets 503. */
  private void callFailing(URI url) {
    CallsTestData.failEveryAttempt(DOWNSTREAM);
    var answer = clients.restClient(CallsTestData.ORDERS).get().uri(url).retrieve();
    assertThatExceptionOfType(RestClientException.class).isThrownBy(answer::toBodilessEntity);
  }

  /** Returns the address of the other service with the path and query as written. */
  private URI sentAs(String pathAndQuery) {
    return URI.create(DOWNSTREAM.url() + pathAndQuery);
  }

  @Test
  @DisplayName("a named secret, a JWT, a card and an IBAN in the url are masked")
  void urlRedacted(CapturedOutput output) {
    callFailing(
        "/orders/{id}?api_key={key}&jwt={jwt}&q={card}&r={iban}",
        CallsTestData.ORDER_ID,
        CallsTestData.API_KEY,
        CallsTestData.JWT,
        CallsTestData.CARD,
        CallsTestData.IBAN);

    assertCallLine(
        output,
        CallsTestData.ORDERS,
        "\"message\":\"HTTP client call failed\"",
        "\"client\":\"orders\"",
        "\"uri\":\"/orders/{id}?api_key=****",
        "\"url\":\"http://127.0.0.1:",
        "/orders/8812?api_key=****&jwt=****",
        "q=****1111",
        "r=****3000",
        "\"status\":503",
        "\"outcome\":\"SERVER_ERROR\"",
        "\"attempts\":4");
    assertThat(output.getAll())
        .doesNotContain(CallsTestData.API_KEY)
        .doesNotContain(CallsTestData.JWT)
        .doesNotContain(CallsTestData.CARD)
        .doesNotContain(CallsTestData.IBAN);
  }

  @Test
  @DisplayName("a plain email in the url is masked by its shape")
  void plainEmailMasked(CapturedOutput output) {
    callFailing(sentAs("/orders?notify=" + CallsTestData.EMAIL));

    assertCallLine(output, CallsTestData.ORDERS, "notify=****.com");
    assertThat(output.getAll()).doesNotContain(CallsTestData.EMAIL);
  }

  @Test
  @DisplayName("a secret joined into a team's own template is masked by its name in uri")
  void joinedSecretMaskedInTemplate(CapturedOutput output) {
    callFailing("/orders?api_key=" + CallsTestData.API_KEY);

    assertCallLine(output, CallsTestData.ORDERS, "\"uri\":\"/orders?api_key=****\"");
    assertThat(output.getAll()).doesNotContain(CallsTestData.API_KEY);
  }

  @Test
  @DisplayName("a user and password in the url are masked as http://****@host")
  void userInfoMasked(CapturedOutput output) {
    callFailing(URI.create("http://orders:pass-7f3e@127.0.0.1:" + DOWNSTREAM.url().split(":")[2]));

    assertCallLine(output, CallsTestData.ORDERS, "\"url\":\"http://****@127.0.0.1");
    assertThat(output.getAll()).doesNotContain("pass-7f3e");
  }

  @Test
  @DisplayName("a 100 KB query is cut by the logging starter's 16 KB limit, in its own field")
  void longUrlCut(CapturedOutput output) {
    callFailing(sentAs("/orders?q=" + "a".repeat(CallsTestData.HOSTILE_LENGTH)));

    var line = assertCallLine(output, CallsTestData.ORDERS, "\"url\":\"...[truncated]\"");
    assertThat(line).contains("\"outcome\":\"SERVER_ERROR\"").hasSizeLessThan(LINE_LIMIT);
  }

  @Test
  @DisplayName("a path value with a line break is written encoded, on one line")
  void lineBreakEncoded(CapturedOutput output) {
    callFailing(CallsTestData.ORDER_TEMPLATE, "a\r\nb");

    assertCallLine(output, CallsTestData.ORDERS, "/orders/a%0D%0Ab");
    assertThat(output.getAll()).doesNotContain("a\r\nb").doesNotContain("a\nb");
  }

  @Nested
  @DisplayName("a value Spring percent-encodes (the gap of issue #273)")
  class EncodedGap {

    @Test
    @DisplayName("an encoded email in a path is written as sent")
    void pathEmail(CapturedOutput output) {
      callFailing(sentAs("/users/jane%40example.com/orders"));

      assertCallLine(output, CallsTestData.ORDERS, "/users/jane%40example.com/orders");
    }

    @Test
    @DisplayName("an encoded email in a parameter with no telling name is written as sent")
    void unnamedParameterEmail(CapturedOutput output) {
      callFailing(sentAs("/orders?notify=jane%40example.com"));

      assertCallLine(output, CallsTestData.ORDERS, "notify=jane%40example.com");
    }

    @Test
    @DisplayName("an encoded card number with spaces is written as sent")
    void unnamedParameterCard(CapturedOutput output) {
      callFailing(sentAs("/orders?q=4111%201111%201111%201111"));

      assertCallLine(output, CallsTestData.ORDERS, "q=4111%201111%201111%201111");
    }

    @Test
    @DisplayName("an encoded IBAN with spaces is written as sent")
    void unnamedParameterIban(CapturedOutput output) {
      callFailing(sentAs("/orders?r=DE89%203704%200044%200532%200130%2000"));

      assertCallLine(output, CallsTestData.ORDERS, "r=DE89%203704%200044%200532%200130%2000");
    }

    @Test
    @DisplayName("an encoded email under a telling name is masked by the name, in its own field")
    void namedParameterMasked(CapturedOutput output) {
      callFailing(sentAs("/orders?email=jane%40example.com"));

      assertCallLine(output, CallsTestData.ORDERS, "\"url\":\"http://127.0.0.1:", "email=****");
      assertThat(output.getAll()).doesNotContain("jane");
    }
  }
}
