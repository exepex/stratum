package com.exepex.stratum.observability;

import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.FailureCauses;
import com.exepex.stratum.core.error.FailureWithErrorCode;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import lombok.RequiredArgsConstructor;

/**
 * Adds the error code a request failed with to its timer and span, as {@code error.code}: the
 * failure's own code, such as {@code ORD-0001}, or that of the first of its causes that has one,
 * else {@code none}. The span keeps it only when there is a code. It reads the failure Spring
 * recorded on the request's observation, so a failure the service's own handler answers is not
 * counted, on Spring MVC as on WebFlux.
 */
@RequiredArgsConstructor
public class RequestErrorCodeFilter implements ObservationFilter {

  /** The name of the request observations, from Spring Boot's setting. */
  private final String serverRequestsName;

  /**
   * Adds the {@code error.code} tag to an incoming request's observation, and leaves any other
   * observation as it is.
   *
   * @param context the observation's data, before it becomes a span and a metric
   * @return the same context
   */
  @Override
  public Observation.Context map(Observation.Context context) {
    if (serverRequestsName.equals(context.getName())) {
      context.addLowCardinalityKeyValue(
          KeyValue.of(ObservabilityConstants.ERROR_CODE_TAG, errorCodeOf(context)));
    }
    return context;
  }

  /** Returns the code of the recorded failure or of one of its causes, else {@code none}. */
  private String errorCodeOf(Observation.Context context) {
    return FailureCauses.findFailureWithErrorCode(context.getError())
        .map(FailureWithErrorCode::getErrorCode)
        .map(ErrorCode::getCode)
        .orElse(ObservabilityConstants.NO_ERROR_CODE);
  }
}
