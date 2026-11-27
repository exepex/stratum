package com.exepex.stratum.resilience.it;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Answers every failure with the service's own status, as some services do. */
@RestControllerAdvice
class CatchAllHandler {

  /**
   * Answers the failure.
   *
   * @param failure what the request failed with
   * @return the answer
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<String> anyFailure(Exception failure) {
    return ResponseEntity.status(ServiceErrorHandlers.CATCH_ALL_STATUS).body("handled");
  }
}
