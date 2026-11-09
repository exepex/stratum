package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.restclient.DownstreamException;
import com.exepex.stratum.restclient.StratumClientCustomizer;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.errors.ErrorDownstream;
import com.exepex.stratum.restclient.testsupport.errors.ErrorsApplication;
import com.exepex.stratum.restclient.testsupport.errors.OrdersClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(
    classes = {ErrorsApplication.class, OptionalNotFoundIntegrationTest.SmallBreakerWindow.class})
@Timeout(30)
@DisplayName("A client method that returns Optional reads a 404 as empty")
// Only 404s and successes are called here, so a breaker that opened could only be a 404's doing.
class OptionalNotFoundIntegrationTest {

  /** The calls the breaker sees before it decides, set small by the test's customizer. */
  private static final int WINDOW = 2;

  /** More 404s than the breaker needs to decide, three windows of them. */
  private static final int NOT_FOUND_CALLS = WINDOW + WINDOW + WINDOW;

  /** Each shape of Optional that Spring answers, called on a 404. */
  private static final List<Named<Function<OrdersClient, Optional<?>>>> SHAPES =
      List.of(
          Named.of("Optional<T>", OrdersClient::findMissing),
          Named.of("Optional<ResponseEntity<T>>", OrdersClient::findMissingAnswer),
          Named.of("Optional<ResponseEntity<Void>>", OrdersClient::findMissingWithoutBody),
          Named.of("Optional<HttpHeaders>", OrdersClient::findMissingHeaders));

  /** The orders service. */
  @AutoClose private static final ErrorDownstream DOWNSTREAM = ErrorDownstream.start();

  @Autowired private OrdersClient orders;

  /**
   * Points the orders client at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    registry.add(RestClientTestData.ORDERS_URL, DOWNSTREAM::url);
  }

  @ParameterizedTest
  @FieldSource("SHAPES")
  @DisplayName("a 404 is empty, for each shape Spring wraps in Optional")
  void notFoundIsEmpty(Function<OrdersClient, Optional<?>> call) {
    assertThat(call.apply(orders)).isEmpty();
  }

  @Test
  @DisplayName("an answer that succeeds is present")
  void foundIsPresent() {
    assertThat(orders.findLargeAnswer()).get().asString().hasSize(ErrorDownstream.LARGE_SIZE);
  }

  @Test
  @DisplayName("a 404 on a method without Optional still throws")
  void plainMethodStillThrows() {
    assertThatExceptionOfType(DownstreamException.class)
        .isThrownBy(orders::missing)
        .satisfies(failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  @DisplayName("another client error on a method with Optional still throws")
  void otherClientErrorStillThrows() {
    assertThatExceptionOfType(DownstreamException.class)
        .isThrownBy(orders::findForbidden)
        .satisfies(failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  @DisplayName("404s read as empty never open the breaker")
  void notFoundNeverOpensBreaker() {
    IntStream.range(0, NOT_FOUND_CALLS).forEach(call -> assertThat(orders.findMissing()).isEmpty());

    assertThat(orders.findLargeAnswer()).isPresent();
  }

  /** Makes the breaker decide after {@link #WINDOW} calls. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SmallBreakerWindow {

    /**
     * Makes the breaker decide after {@link #WINDOW} calls.
     *
     * @return the customizer
     */
    @Bean
    StratumClientCustomizer smallBreakerWindow() {
      return new StratumClientCustomizer() {
        @Override
        public void customize(String client, RestClient.Builder builder) {
          // The client stays as Stratum sets it.
        }

        @Override
        public void customizeCircuitBreaker(String client, CircuitBreakerConfig.Builder breaker) {
          breaker.slidingWindowSize(WINDOW).minimumNumberOfCalls(WINDOW);
        }
      };
    }
  }
}
