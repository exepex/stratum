package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.FailureWithErrorCode;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.test.OrderErrorCode;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import java.io.Serial;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("RequestErrorCodeFilter adds the error code a request failed with to its observation")
class RequestErrorCodeFilterTest {

  /** The code of the orders' duplicate order. */
  private static final String DUPLICATE = OrderErrorCode.DUPLICATE_ORDER.getCode();

  /** Failures as Spring records them, each with the tag value that must be added. */
  private static final List<Arguments> FAILURES_AND_CODES =
      List.of(
          Arguments.of(new StratumException(OrderErrorCode.DUPLICATE_ORDER), DUPLICATE),
          Arguments.of(
              new IllegalStateException(new StratumException(OrderErrorCode.DUPLICATE_ORDER)),
              DUPLICATE),
          Arguments.of(new IllegalStateException("no code"), ObservabilityConstants.NO_ERROR_CODE),
          Arguments.of(new NullCodeException(), ObservabilityConstants.NO_ERROR_CODE));

  /** A name a service gives its request observations through Spring Boot's key. */
  private static final String RENAMED_SERVER_REQUESTS = "orders.requests";

  private final RequestErrorCodeFilter filter =
      new RequestErrorCodeFilter(ObservabilityConstants.DEFAULT_SERVER_REQUESTS_NAME);

  /** Returns the request observation's data after the filter ran, with the failure recorded. */
  private Observation.Context filtered(
      RequestErrorCodeFilter requestFilter, String name, @Nullable Throwable failure) {
    var context = new Observation.Context();
    context.setName(name);
    if (failure != null) {
      context.setError(failure);
    }
    return requestFilter.map(context);
  }

  @ParameterizedTest(name = "{0}")
  @FieldSource("FAILURES_AND_CODES")
  @DisplayName("adds the failure's own code, else its first cause's, else none")
  void addsCode(Throwable failure, String code) {
    assertThat(
            filtered(filter, ObservabilityConstants.DEFAULT_SERVER_REQUESTS_NAME, failure)
                .getLowCardinalityKeyValue(ObservabilityConstants.ERROR_CODE_TAG))
        .isEqualTo(KeyValue.of(ObservabilityConstants.ERROR_CODE_TAG, code));
  }

  @Test
  @DisplayName("adds none to a request that succeeded")
  void addsNoneOnSuccess() {
    assertThat(
            filtered(filter, ObservabilityConstants.DEFAULT_SERVER_REQUESTS_NAME, null)
                .getLowCardinalityKeyValue(ObservabilityConstants.ERROR_CODE_TAG))
        .isEqualTo(
            KeyValue.of(
                ObservabilityConstants.ERROR_CODE_TAG, ObservabilityConstants.NO_ERROR_CODE));
  }

  @Test
  @DisplayName("leaves another observation, such as an outgoing call, as it is")
  void leavesOtherObservations() {
    var failure = new StratumException(OrderErrorCode.DUPLICATE_ORDER);

    assertThat(
            filtered(filter, ObservabilityConstants.DEFAULT_CLIENT_REQUESTS_NAME, failure)
                .getLowCardinalityKeyValue(ObservabilityConstants.ERROR_CODE_TAG))
        .isNull();
  }

  @Test
  @DisplayName("tags the request observations under the name Spring Boot's key gives them")
  void followsRenamedObservations() {
    var renamedFilter = new RequestErrorCodeFilter(RENAMED_SERVER_REQUESTS);
    var failure = new StratumException(OrderErrorCode.DUPLICATE_ORDER);

    assertThat(
            List.of(
                filtered(renamedFilter, RENAMED_SERVER_REQUESTS, failure),
                filtered(
                    renamedFilter, ObservabilityConstants.DEFAULT_SERVER_REQUESTS_NAME, failure)))
        .extracting(
            context -> context.getLowCardinalityKeyValue(ObservabilityConstants.ERROR_CODE_TAG))
        .containsExactly(KeyValue.of(ObservabilityConstants.ERROR_CODE_TAG, DUPLICATE), null);
  }

  /** A service's exception whose code is missing, as a faulty implementation can return. */
  static class NullCodeException extends RuntimeException implements FailureWithErrorCode {

    @Serial private static final long serialVersionUID = 1L;

    /**
     * Returns no code.
     *
     * @return {@code null}
     */
    @Override
    public @Nullable ErrorCode getErrorCode() {
      return null;
    }

    /**
     * Returns no detail.
     *
     * @return an empty detail
     */
    @Override
    public String getErrorDetail() {
      return "";
    }
  }
}
