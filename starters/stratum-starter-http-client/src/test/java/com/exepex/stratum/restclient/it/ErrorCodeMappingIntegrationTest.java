package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.restclient.ClientResilience;
import com.exepex.stratum.restclient.DownstreamException;
import com.exepex.stratum.restclient.StratumClientCustomizer;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.QueuedDownstream;
import com.exepex.stratum.restclient.testsupport.mapped.MappedApplication;
import com.exepex.stratum.restclient.testsupport.mapped.MappedTestData;
import com.exepex.stratum.restclient.testsupport.mapped.StockClient;
import java.io.Serial;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(
    classes = {MappedApplication.class, ErrorCodeMappingIntegrationTest.TeapotMapper.class},
    properties = {
      MappedTestData.DEFAULT_ERRORS + "[401]=" + MappedTestData.UNAUTHORIZED_CODE,
      MappedTestData.DEFAULT_ERRORS + "[409]=" + MappedTestData.SHARED_CONFLICT_CODE,
      MappedTestData.DEFAULT_ERRORS + "[404]=" + MappedTestData.SHARED_NOT_FOUND_CODE,
      MappedTestData.STOCK_ERRORS + "[409]=" + MappedTestData.CONFLICT_CODE,
      MappedTestData.STOCK_ERRORS + "[404]=" + MappedTestData.NOT_FOUND_CODE,
      MappedTestData.STOCK_ERRORS + "[503]=" + MappedTestData.UNAVAILABLE_CODE,
      MappedTestData.STOCK_ERRORS + "[418]=" + MappedTestData.TEAPOT_CODE,
      MappedTestData.LIST_ORDERS_ERRORS + "[404]=" + MappedTestData.LIST_NOT_FOUND_CODE,
      MappedTestData.STOCK_SETTINGS
          + ".retry.max-retries="
          + ErrorCodeMappingIntegrationTest.RETRIES,
      MappedTestData.STOCK_SETTINGS + ".retry.delay=10ms"
    })
@Timeout(30)
@DisplayName(
    "A client's error answer carries the service's own code where its errors map the status")
class ErrorCodeMappingIntegrationTest {

  /** The stock client's retries. */
  static final int RETRIES = 2;

  /** The stock service. */
  @AutoClose private static final QueuedDownstream STOCK = QueuedDownstream.start();

  /** The code the customizer saw on the last 418, before it threw the team's exception. */
  private static final AtomicReference<ErrorCode> SEEN_BY_MAPPER = new AtomicReference<>();

  @Autowired private StockClient stock;

  @Autowired private ClientResilience resilience;

  /**
   * Points the stock client at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    registry.add(MappedTestData.STOCK_URL, STOCK::url);
  }

  /** Returns the failure of one call of the stock client answered with the status. */
  private DownstreamException failureOf(HttpStatus status, Runnable call) {
    STOCK.answerNext(status.value());
    return catchThrowableOfType(DownstreamException.class, call::run);
  }

  @Nested
  @DisplayName("a listed status, outside a web request as a job or listener calls it")
  class Listed {

    @Test
    @DisplayName("stays a DownstreamException with the client's code, status, headers and body")
    void carriesTheClientsCode() {
      var failure = failureOf(HttpStatus.CONFLICT, stock::orders);

      assertThat(failure.getErrorCode().getCode()).isEqualTo(MappedTestData.CONFLICT_CODE);
      assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(failure.getResponseHeaders().getFirst(QueuedDownstream.ANSWER_NUMBER))
          .isNotBlank();
      assertThat(failure.getResponseBodyAsString()).startsWith(QueuedDownstream.ERROR_BODY);
    }

    @Test
    @DisplayName("a status only the defaults map gets the defaults' code")
    void defaultsOnlyStatus() {
      assertThat(failureOf(HttpStatus.UNAUTHORIZED, stock::orders).getErrorCode().getCode())
          .isEqualTo(MappedTestData.UNAUTHORIZED_CODE);
    }

    @Test
    @DisplayName("a status at all levels gets the operation's code, and the client's elsewhere")
    void operationOverClientOverDefaults() {
      assertThat(failureOf(HttpStatus.NOT_FOUND, stock::listOrders).getErrorCode().getCode())
          .isEqualTo(MappedTestData.LIST_NOT_FOUND_CODE);
      assertThat(failureOf(HttpStatus.NOT_FOUND, stock::orders).getErrorCode().getCode())
          .isEqualTo(MappedTestData.NOT_FOUND_CODE);
    }

    @Test
    @DisplayName("a 503 is still retried and counted by the breaker, then carries the code")
    void retriesAndBreakerUnchanged() {
      final var requestsBefore = STOCK.requests().size();
      final var failedBefore = CallChecks.failedCalls(resilience, MappedTestData.STOCK);
      STOCK.answerNext(
          HttpStatus.SERVICE_UNAVAILABLE.value(),
          HttpStatus.SERVICE_UNAVAILABLE.value(),
          HttpStatus.SERVICE_UNAVAILABLE.value());

      assertThatExceptionOfType(DownstreamException.class)
          .isThrownBy(stock::orders)
          .satisfies(
              failure ->
                  assertThat(failure.getErrorCode().getCode())
                      .isEqualTo(MappedTestData.UNAVAILABLE_CODE));
      assertThat(STOCK.requests()).hasSize(requestsBefore + RETRIES + 1);
      assertThat(CallChecks.failedCalls(resilience, MappedTestData.STOCK))
          .isEqualTo(failedBefore + RETRIES + 1);
    }

    @Test
    @DisplayName("mapError sees the code first, and the team's exception it throws wins")
    void mapErrorWins() {
      STOCK.answerNext(HttpStatus.I_AM_A_TEAPOT.value());

      assertThatExceptionOfType(TeapotException.class).isThrownBy(stock::orders);
      assertThat(SEEN_BY_MAPPER.get().getCode()).isEqualTo(MappedTestData.TEAPOT_CODE);
    }
  }

  @Nested
  @DisplayName("a status no setting maps")
  class Unlisted {

    @Test
    @DisplayName("keeps SX-HTTPC-0007 for a final answer")
    void finalAnswerKeepsCode() {
      assertThat(failureOf(HttpStatus.BAD_REQUEST, stock::listOrders).getErrorCode())
          .isEqualTo(HttpClientErrorCode.DOWNSTREAM_ERROR);
    }

    @Test
    @DisplayName("keeps SX-HTTPC-0009 for a refusal for now, after the retries")
    void refusalForNowKeepsCode() {
      STOCK.answerNext(
          HttpStatus.BAD_GATEWAY.value(),
          HttpStatus.BAD_GATEWAY.value(),
          HttpStatus.BAD_GATEWAY.value());

      assertThatExceptionOfType(DownstreamException.class)
          .isThrownBy(stock::orders)
          .satisfies(
              failure ->
                  assertThat(failure.getErrorCode())
                      .isEqualTo(HttpClientErrorCode.DOWNSTREAM_REFUSED_FOR_NOW));
    }
  }

  /** The team's exception for a 418. */
  static class TeapotException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    /**
     * Makes the exception.
     *
     * @param cause the error answer
     */
    TeapotException(DownstreamException cause) {
      super(cause);
    }
  }

  /** A customizer that records the code of a 418 and throws the team's exception for it. */
  @TestConfiguration(proxyBeanMethods = false)
  static class TeapotMapper {

    /**
     * Maps a 418 to the team's exception.
     *
     * @return the customizer
     */
    @Bean
    StratumClientCustomizer teapotMapper() {
      return new StratumClientCustomizer() {
        @Override
        public void customize(String client, RestClient.Builder builder) {
          // Only the mapping changes.
        }

        @Override
        public void mapError(String client, DownstreamException failure) {
          if (failure.getStatusCode().value() == HttpStatus.I_AM_A_TEAPOT.value()) {
            SEEN_BY_MAPPER.set(failure.getErrorCode());
            throw new TeapotException(failure);
          }
        }
      };
    }
  }
}
