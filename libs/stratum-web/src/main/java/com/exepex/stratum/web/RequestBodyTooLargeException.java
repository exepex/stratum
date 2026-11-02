package com.exepex.stratum.web;

import com.exepex.stratum.core.api.StratumInternal;
import java.io.Serial;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

/**
 * Answers a request whose body is larger than its limit with {@code 413 Content Too Large}. Its
 * error body carries {@code SX-WEB-1008}.
 */
@StratumInternal
public class RequestBodyTooLargeException extends ResponseStatusException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Makes the exception.
   *
   * @param limit how large the body could be
   */
  public RequestBodyTooLargeException(DataSize limit) {
    super(HttpStatus.CONTENT_TOO_LARGE, "The request body is larger than its limit of " + limit);
  }
}
