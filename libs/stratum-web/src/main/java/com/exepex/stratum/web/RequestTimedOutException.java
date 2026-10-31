package com.exepex.stratum.web;

import java.io.Serial;
import java.time.Duration;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * Answers a request that ran out of time before its response started with the status of {@code
 * stratum.web.timeout.status}. Its error body carries {@code SX-WEB-1007}.
 */
public class RequestTimedOutException extends ResponseStatusException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Makes the exception.
   *
   * @param status the status the request is answered with
   * @param limit how long the request had
   */
  public RequestTimedOutException(HttpStatusCode status, Duration limit) {
    super(status, "The request ran out of time after " + limit);
  }
}
