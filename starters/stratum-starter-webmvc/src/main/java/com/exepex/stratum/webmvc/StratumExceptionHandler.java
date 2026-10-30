package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.web.ErrorResponseFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Answers a {@link StratumException} thrown by a controller with Stratum's error body and the
 * status mapped to its code. It runs last, so the service's own handlers win.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class StratumExceptionHandler {

  /** Makes the error body and logs the failure. */
  private final ErrorResponseFactory errorResponseFactory;

  /**
   * Returns the error body of the exception, which holds its status.
   *
   * @param failure the exception a controller threw
   * @return the error body
   */
  @ExceptionHandler(StratumException.class)
  public ProblemDetail answerStratumException(StratumException failure) {
    return errorResponseFactory.forStratumException(failure);
  }
}
