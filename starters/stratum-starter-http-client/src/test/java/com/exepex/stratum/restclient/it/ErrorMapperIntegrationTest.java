package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.restclient.DownstreamException;
import com.exepex.stratum.restclient.StratumClientCustomizer;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.errors.ErrorDownstream;
import com.exepex.stratum.restclient.testsupport.errors.ErrorsApplication;
import com.exepex.stratum.restclient.testsupport.errors.OrdersClient;
import java.io.Serial;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(classes = {ErrorsApplication.class, ErrorMapperIntegrationTest.Mappers.class})
@Timeout(30)
@DisplayName("A StratumClientCustomizer maps a client's error answer to the team's own exception")
class ErrorMapperIntegrationTest {

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

  @Test
  @DisplayName("the first customizer, in their order, that throws its own exception wins")
  void firstMappingWins() {
    assertThatExceptionOfType(OrderMissingException.class).isThrownBy(orders::missing);
  }

  @Test
  @DisplayName("a 404 of a method that returns Optional is empty: no customizer is asked")
  void optionalNotFoundNotMapped() {
    assertThat(orders.findMissing()).isEmpty();
  }

  @Test
  @DisplayName("an answer no customizer replaces stays a DownstreamException, Optional or not")
  void unmappedAnswerKept() {
    assertThatExceptionOfType(DownstreamException.class)
        .isThrownBy(() -> orders.order("o-1", "c-1"));
    assertThatExceptionOfType(DownstreamException.class).isThrownBy(() -> orders.findOrder("o-1"));
  }

  /** The team's exception for an order the orders service does not have. */
  static class OrderMissingException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    /**
     * Makes the exception.
     *
     * @param cause the error answer
     */
    OrderMissingException(DownstreamException cause) {
      super(cause);
    }
  }

  /** Two customizers that map a 404: the first in order wins. */
  @TestConfiguration(proxyBeanMethods = false)
  static class Mappers {

    /**
     * Maps a 404 to the team's exception.
     *
     * @return the customizer
     */
    @Bean
    @Order(1)
    StratumClientCustomizer orderMissing() {
      return new NotFoundMapper(true);
    }

    /**
     * Maps a 404 to another exception, which loses to the first.
     *
     * @return the customizer
     */
    @Bean
    @Order(2)
    StratumClientCustomizer anyMissing() {
      return new NotFoundMapper(false);
    }
  }

  /**
   * Throws the team's exception or {@code IllegalStateException} for a 404, and keeps the rest.
   *
   * @param team whether it maps to the team's exception
   */
  record NotFoundMapper(boolean team) implements StratumClientCustomizer {

    @Override
    public void customize(String client, RestClient.Builder builder) {
      // Only the mapping changes.
    }

    @Override
    public void mapError(String client, DownstreamException failure) {
      if (failure.getStatusCode().value() != HttpStatus.NOT_FOUND.value()) {
        return;
      }
      if (team) {
        throw new OrderMissingException(failure);
      }
      throw new IllegalStateException(client);
    }
  }
}
