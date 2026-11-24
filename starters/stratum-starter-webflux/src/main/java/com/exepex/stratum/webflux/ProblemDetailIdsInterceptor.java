package com.exepex.stratum.webflux;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.web.ErrorResponseFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Adds the request's trace and correlation IDs to a {@link ProblemDetail} that the service's own
 * exception handler returns, and the request path as its {@code instance} unless the handler set
 * one, and leaves the rest of the body as the service made it. It registers itself with Spring
 * WebFlux, which calls it before writing any Problem Details body.
 */
@RequiredArgsConstructor
public class ProblemDetailIdsInterceptor implements WebFluxConfigurer, ErrorResponse.Interceptor {

  /** Adds the IDs and the request path. */
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
   * Adds the IDs from the logging context and the request path to the body, unless it has them.
   * Spring WebFlux calls this method.
   *
   * @param detail the body
   * @param errorResponse the error behind it, if Spring knows one
   * @param request the request
   */
  @Override
  public void handleError(
      ProblemDetail detail, @Nullable ErrorResponse errorResponse, HttpRequest request) {
    handleError(detail, errorResponse);
    errorResponseFactory.addInstanceIfMissing(detail, request.getURI());
  }

  /**
   * Adds the IDs from the logging context to the body, unless it has them.
   *
   * @param detail the body
   * @param errorResponse the error behind it, if Spring knows one
   */
  @Override
  public void handleError(ProblemDetail detail, @Nullable ErrorResponse errorResponse) {
    errorResponseFactory.addRequestIds(
        detail,
        MDC.get(LoggingContextConstants.TRACE_ID),
        MDC.get(LoggingContextConstants.CORRELATION_ID));
  }
}
