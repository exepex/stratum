package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;

/** How a call of a Stratum client ended, after its retries, as its log line and meters show. */
@StratumInternal
public enum CallOutcome {

  /** An answer that is not an error, or a 404 that a method returning {@code Optional} reads. */
  SUCCESS,

  /** An error answer of 4xx. */
  CLIENT_ERROR,

  /** An error answer of 5xx. */
  SERVER_ERROR,

  /** No answer came within the answer timeout. */
  TIMEOUT,

  /**
   * No answer for any other reason: a failed connection, a full pool, a dropped connection or a
   * service token that could not be fetched.
   */
  UNAVAILABLE,

  /** A failure inside the service, such as an answer that cannot be read. */
  ERROR,

  /** The open circuit breaker refused the call before any attempt was sent. */
  REFUSED
}
