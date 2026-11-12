package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.restclient.ClientResilience;
import com.exepex.stratum.restclient.StratumClientCustomizer;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.errors.ErrorDownstream;
import com.exepex.stratum.restclient.testsupport.errors.ErrorsApplication;
import com.exepex.stratum.restclient.testsupport.errors.JsonLibrary;
import com.exepex.stratum.restclient.testsupport.errors.OrdersClient;
import com.google.gson.stream.MalformedJsonException;
import com.google.protobuf.InvalidProtocolBufferException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.EOFException;
import java.net.SocketTimeoutException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.protobuf.ProtobufHttpMessageConverter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClientException;

@SpringBootTest(
    classes = {ErrorsApplication.class, GsonProtobufClientIntegrationTest.GsonAndProtobuf.class},
    properties = {
      HttpClientConstants.CLIENTS + "." + RestClientTestData.ORDERS + ".timeout=300ms",
      HttpClientConstants.DEFAULTS + ".retry.delay=10ms",
      HttpClientConstants.DEFAULTS
          + ".retry.max-retries="
          + GsonProtobufClientIntegrationTest.RETRIES
    })
@Timeout(30)
@DisplayName(
    "A client that reads Gson's JSON or protobuf: a bad body is an answer, a cut one is not")
class GsonProtobufClientIntegrationTest {

  /** How many times a call is sent again. */
  static final int RETRIES = 2;

  /** The orders service. */
  @AutoClose private static final ErrorDownstream DOWNSTREAM = ErrorDownstream.start();

  @Autowired private OrdersClient orders;

  @Autowired private ClientResilience resilience;

  /**
   * Points the orders client at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    registry.add(RestClientTestData.ORDERS_URL, DOWNSTREAM::url);
  }

  /** Closes the breaker and starts its counts from zero. */
  @BeforeEach
  void resetBreaker() {
    breaker().reset();
  }

  @Nested
  @DisplayName("with Gson's converter")
  class Gson {

    @Test
    @DisplayName("bad JSON stays Spring's exception, sent once and not counted by the breaker")
    void badJsonIsAnAnswer() {
      assertIsAnAnswer(orders::badJson, MalformedJsonException.class);
    }

    @Test
    @DisplayName("JSON that ends inside a list stays Spring's exception, sent once, not counted")
    void shortJsonIsAnAnswer() {
      assertIsAnAnswer(orders::shortJson, EOFException.class);
    }

    @Test
    @DisplayName("a body cut off by the timeout ends with SX-HTTPC-0001, is resent and counted")
    void cutOffIsTimeout() {
      assertIsTimeout(orders::cutOrder);
    }
  }

  @Nested
  @DisplayName("with the protobuf converter")
  class Protobuf {

    @Test
    @DisplayName("bytes that are not protobuf stay Spring's exception, sent once, not counted")
    void badProtobufIsAnAnswer() {
      assertIsAnAnswer(orders::badProtobuf, InvalidProtocolBufferException.class);
    }

    @Test
    @DisplayName("a body cut off by the timeout ends with SX-HTTPC-0001, is resent and counted")
    void cutOffIsTimeout() {
      assertIsTimeout(orders::cutProtobuf);
    }
  }

  /** Checks that a call's bad body is Spring's exception, sent once and not counted. */
  private void assertIsAnAnswer(ThrowingCallable call, Class<? extends Throwable> parserFailure) {
    var requestsBefore = DOWNSTREAM.bodyRequests();

    assertThatExceptionOfType(RestClientException.class)
        .isThrownBy(call)
        .withRootCauseInstanceOf(parserFailure);
    assertThat(DOWNSTREAM.bodyRequests() - requestsBefore).isEqualTo(1);
    assertThat(breaker().getMetrics().getNumberOfFailedCalls()).isZero();
  }

  /** Checks that a call's cut-off body ends with 0001, each attempt sent and counted. */
  private void assertIsTimeout(ThrowingCallable call) {
    var requestsBefore = DOWNSTREAM.bodyRequests();

    CallChecks.assertEndsWith(call, HttpClientErrorCode.DOWNSTREAM_TIMEOUT)
        .withRootCauseInstanceOf(SocketTimeoutException.class);
    assertThat(DOWNSTREAM.bodyRequests() - requestsBefore).isEqualTo(1 + RETRIES);
    assertThat(breaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(1 + RETRIES);
  }

  /** Returns the orders client's breaker. */
  private CircuitBreaker breaker() {
    return resilience.circuitBreaker(RestClientTestData.ORDERS).orElseThrow();
  }

  /** Gives the orders client Gson's JSON converter and the protobuf one, as a customizer would. */
  @TestConfiguration(proxyBeanMethods = false)
  static class GsonAndProtobuf {

    /**
     * Sets Gson's converter and the protobuf one on every client of this test.
     *
     * @return the customizer
     */
    @Bean
    StratumClientCustomizer gsonAndProtobuf() {
      return (client, builder) ->
          builder.configureMessageConverters(
              converters ->
                  converters
                      .withJsonConverter(JsonLibrary.GSON.converter())
                      .addCustomConverter(new ProtobufHttpMessageConverter()));
    }
  }
}
