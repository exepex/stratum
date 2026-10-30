package com.exepex.stratum.webflux;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.web.ErrorResponseFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Adds the request's trace and correlation IDs to a {@link ProblemDetail} that the service's own
 * exception handler returns, and leaves the rest of the body as the service made it. It registers
 * itself with Spring WebFlux, which calls it before writing any Problem Details body.
 */
@RequiredArgsConstructor
public class ProblemDetailIdsInterceptor implements WebFluxConfigurer, ErrorResponse.Interceptor {

  /** Adds the IDs. */
  private final ErrorResponseFactory errorResponseFactory;

  /**
   * Registers this interceptor with Spring WebFlux.
   *
   * @param interceptors the interceptors Spring WebFlux calls
   */
  @Override
  public void addErrorResponseInterceptors(List<ErrorResponse.Interceptor> interceptors) {
    interceptors.add(this);
  }

  /**
   * Adds the IDs from the logging context to the body, unless it has them.
   *
   * @param detail the body
   * @param errorResponse the error behind it, if Spring knows one
   */
  @Override
  public void handleError(ProblemDetail detail, ErrorResponse errorResponse) {
    errorResponseFactory.addRequestIds(
        detail,
        MDC.get(LoggingContextConstants.TRACE_ID),
        MDC.get(LoggingContextConstants.CORRELATION_ID));
  }
}
