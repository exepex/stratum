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
import com.fasterxml.jackson.core.JsonParseException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClientException;

@SpringBootTest(
    classes = {ErrorsApplication.class, Jackson2ClientIntegrationTest.Jackson2Json.class},
    properties = {
      HttpClientConstants.CLIENTS + "." + RestClientTestData.ORDERS + ".timeout=300ms",
      HttpClientConstants.DEFAULTS + ".retry.delay=10ms",
      HttpClientConstants.DEFAULTS + ".retry.max-retries=2"
    })
@Timeout(30)
@DisplayName("A client that reads JSON with Jackson 2's converter: bad JSON is an answer")
class Jackson2ClientIntegrationTest {

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

  @Test
  @DisplayName("bad JSON stays Spring's exception, sent once and not counted by the breaker")
  void badJsonIsAnAnswer() {
    var requestsBefore = DOWNSTREAM.badJsonRequests();

    assertThatExceptionOfType(RestClientException.class)
        .isThrownBy(orders::badJson)
        .withRootCauseInstanceOf(JsonParseException.class);
    assertThat(DOWNSTREAM.badJsonRequests() - requestsBefore).isEqualTo(1);
    assertThat(breaker().getMetrics().getNumberOfFailedCalls()).isZero();
  }

  @Test
  @DisplayName("a record's body cut off inside its list by the timeout ends with SX-HTTPC-0001")
  void cutOffInsideListIsTimeout() {
    CallChecks.assertEndsWith(orders::cutOrder, HttpClientErrorCode.DOWNSTREAM_TIMEOUT)
        .withRootCauseInstanceOf(SocketTimeoutException.class);
  }

  /** Returns the orders client's breaker. */
  private CircuitBreaker breaker() {
    return resilience.circuitBreaker(RestClientTestData.ORDERS).orElseThrow();
  }

  /** Gives the orders client Jackson 2's JSON converter, as a team's customizer would. */
  @TestConfiguration(proxyBeanMethods = false)
  static class Jackson2Json {

    /**
     * Sets Jackson 2's converter on every client of this test.
     *
     * @return the customizer
     */
    @Bean
    StratumClientCustomizer jackson2Json() {
      return (client, builder) ->
          builder.configureMessageConverters(
              converters -> converters.withJsonConverter(JsonLibrary.JACKSON_2.converter()));
    }
  }
}
