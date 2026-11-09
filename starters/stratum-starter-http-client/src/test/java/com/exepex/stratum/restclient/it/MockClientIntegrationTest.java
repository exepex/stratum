package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.restclient.ClientResilience;
import com.exepex.stratum.restclient.MockClient;
import com.exepex.stratum.restclient.MockClients;
import com.exepex.stratum.restclient.RecordedCall;
import com.exepex.stratum.restclient.StratumHttpClients;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.payments.Payment;
import com.exepex.stratum.restclient.testsupport.payments.PaymentClient;
import com.exepex.stratum.restclient.testsupport.payments.PaymentMissingException;
import com.exepex.stratum.restclient.testsupport.payments.PaymentsApplication;
import com.exepex.stratum.restclient.testsupport.payments.PaymentsSetup;
import com.exepex.stratum.test.SilentPort;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@SpringBootTest(
    classes = PaymentsApplication.class,
    properties = {
      RestClientTestData.PAYMENTS_SETTINGS + ".mock=true",
      RestClientTestData.PAYMENTS_SETTINGS + ".auth.bearer=" + RestClientTestData.SECRET,
      RestClientTestData.PAYMENTS_SETTINGS + ".retries=1",
      RestClientTestData.PAYMENTS_SETTINGS + ".retry-delay=50ms",
      RestClientTestData.PAYMENTS_SETTINGS + ".circuit-breaker=false"
    })
@Timeout(30)
@DisplayName("A client with mock: true answers from files and scripts and never calls its service")
class MockClientIntegrationTest {

  /** The payment a test sends. */
  private static final Payment NEW_PAYMENT = new Payment("p-1", "NEW");

  /** The payment the file createPayment.json holds. */
  private static final Payment FILE_PAYMENT = new Payment("p-file", "PENDING");

  /** The payment a test scripts. */
  private static final Payment SCRIPTED_PAYMENT = new Payment("p-2", "PAID");

  /** A delay within the client's timeout. */
  private static final Duration DELAY = Duration.ofMillis(200);

  /** The method with an answer file. */
  private static final String CREATE_PAYMENT = "createPayment";

  /** The method with no answer file. */
  private static final String PAYMENT = "payment";

  /** The client's address: a port that takes no connection, so a real call would time out. */
  private static final SilentPort SERVICE_DOWN = new SilentPort();

  @Autowired private PaymentClient payments;

  @Autowired private MockClients mockClients;

  @Autowired private PaymentsSetup paymentsSetup;

  @Autowired private StratumHttpClients stratumHttpClients;

  @Autowired private ClientResilience clientResilience;

  /** The mock of the payments client, asked for before each test. */
  private MockClient<PaymentClient> paymentsMock;

  /**
   * Points the payments client at the silent port.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void address(DynamicPropertyRegistry registry) {
    registry.add(
        RestClientTestData.PAYMENTS_SETTINGS + ".url",
        () -> "http://" + SERVICE_DOWN.host() + ":" + SERVICE_DOWN.port());
  }

  /** Closes the silent port. */
  @AfterAll
  static void closePort() throws IOException {
    SERVICE_DOWN.close();
  }

  /** Asks for the payments client's mock, which starts recording its calls. */
  @BeforeEach
  void askForMock() {
    paymentsMock = mockClients.of(PaymentClient.class);
  }

  @Nested
  @DisplayName("an answer")
  class Answers {

    @Test
    @DisplayName("comes from the method's file, with no connection and no credential")
    void fromFile() {
      var startedAt = System.nanoTime();

      assertThat(payments.createPayment(NEW_PAYMENT)).isEqualTo(FILE_PAYMENT);
      assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
          .isLessThan(RestClientTestData.FAILS_WITHIN);
      assertThat(paymentsSetup.lastAuthorization()).isNull();
    }

    @Test
    @DisplayName("comes from a script, which wins over the file, and the call is recorded")
    void scriptWins() {
      paymentsMock.on(CREATE_PAYMENT).respond(SCRIPTED_PAYMENT);

      assertThat(payments.createPayment(NEW_PAYMENT)).isEqualTo(SCRIPTED_PAYMENT);
      assertThat(paymentsMock.calls(CREATE_PAYMENT))
          .containsExactly(new RecordedCall(CREATE_PAYMENT, List.of(NEW_PAYMENT)));
    }

    @Test
    @DisplayName("is read anew on each call, so a caller's change never reaches the next answer")
    void readAnewEachCall() {
      var first = payments.lastPayment();
      var unchanged = Map.copyOf(first);
      first.put("status", "CHANGED");

      assertThat(payments.lastPayment()).isEqualTo(unchanged);
    }

    @Test
    @DisplayName("of a method that reads no body needs no file")
    void noBodyNeedsNoFile() {
      payments.cancel(NEW_PAYMENT.id());

      assertThat(payments.paymentHeaders(NEW_PAYMENT.id())).isNotNull();
    }

    @Test
    @DisplayName("that is missing fails the call with 501, naming the file to add")
    void missingAnswerIs501() {
      assertThatExceptionOfType(HttpServerErrorException.class)
          .isThrownBy(() -> payments.payment(NEW_PAYMENT.id()))
          .satisfies(
              failure ->
                  assertThat(failure.getResponseBodyAsString())
                      .isEqualTo(
                          "no mock answer for client payments, method payment: add"
                              + " stratum-mocks/payments/payment.json or script it with"
                              + " MockClient"));
    }

    @Test
    @DisplayName("through stratumHttpClients.restClient is a 501 naming the client")
    void restClientOfClientIs501() {
      var restClient = stratumHttpClients.restClient(RestClientTestData.PAYMENTS);

      assertThatExceptionOfType(HttpServerErrorException.class)
          .isThrownBy(() -> restClient.get().uri("/payments").retrieve().body(String.class))
          .satisfies(
              failure ->
                  assertThat(failure.getResponseBodyAsString())
                      .startsWith("no mock answer for client payments: only the methods"));
    }
  }

  @Nested
  @DisplayName("a scripted failure")
  class Failures {

    @Test
    @DisplayName("of 404 reaches the team's status handler")
    void teamStatusHandlerSeesIt() {
      paymentsMock.on(PAYMENT).fail(HttpStatus.NOT_FOUND);

      assertThatExceptionOfType(PaymentMissingException.class)
          .isThrownBy(() -> payments.payment(NEW_PAYMENT.id()));
    }

    @Test
    @DisplayName("of 503 is retried as a real answer is, and recorded once")
    void retriedAsReal() {
      var retry = clientResilience.retry(RestClientTestData.PAYMENTS).getMetrics();
      var retriedBefore = retry.getNumberOfFailedCallsWithRetryAttempt();
      paymentsMock.on(PAYMENT).fail(HttpStatus.SERVICE_UNAVAILABLE);

      assertThatExceptionOfType(HttpServerErrorException.class)
          .isThrownBy(() -> payments.payment(NEW_PAYMENT.id()));
      assertThat(retry.getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(retriedBefore + 1);
      assertThat(paymentsMock.calls(PAYMENT)).hasSize(1);
    }
  }

  @Nested
  @DisplayName("a delay")
  class Delays {

    @Test
    @DisplayName("within the client's timeout answers after the delay")
    void withinLimitAnswers() {
      paymentsMock.on(PAYMENT).delay(DELAY).respond(SCRIPTED_PAYMENT);
      var startedAt = System.nanoTime();

      assertThat(payments.payment(NEW_PAYMENT.id())).isEqualTo(SCRIPTED_PAYMENT);
      assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isGreaterThanOrEqualTo(DELAY);
    }
  }

  @Nested
  @TestPropertySource(
      properties = {
        RestClientTestData.PAYMENTS_SETTINGS + ".timeout=300ms",
        RestClientTestData.PAYMENTS_SETTINGS + ".total-timeout=500ms"
      })
  @DisplayName("a delay beyond the time limits")
  class DelayBeyondLimits {

    @Test
    @DisplayName("fails the call as a read timeout, ended by total-timeout")
    void endsByTotalTimeout() {
      paymentsMock.on(PAYMENT).delay(RestClientTestData.WAIT).respond(SCRIPTED_PAYMENT);
      var startedAt = System.nanoTime();

      assertThatExceptionOfType(ResourceAccessException.class)
          .isThrownBy(() -> payments.payment(NEW_PAYMENT.id()));
      assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
          .isLessThan(RestClientTestData.FAILS_WITHIN);
    }
  }

  @Nested
  @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
  @DisplayName("scripts are cleared after each test")
  class Reset {

    @Test
    @Order(1)
    @DisplayName("a script set in one test")
    void scriptInFirstTest() {
      paymentsMock.on(CREATE_PAYMENT).respond(SCRIPTED_PAYMENT);

      assertThat(payments.createPayment(NEW_PAYMENT)).isEqualTo(SCRIPTED_PAYMENT);
    }

    @Test
    @Order(2)
    @DisplayName("is gone in the next, which gets the file's answer")
    void fileInSecondTest() {
      assertThat(payments.createPayment(NEW_PAYMENT)).isEqualTo(FILE_PAYMENT);
      assertThat(paymentsMock.calls(CREATE_PAYMENT)).hasSize(1);
    }
  }
}
