package com.exepex.stratum.web;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The codes of the web module, each listed with its fix in {@code docs/guides/error-codes.md}. A
 * caller sees all but {@code SX-WEB-1006} in the error body of a request that failed outside the
 * service's own error handling; {@code SX-WEB-1006} stops the service at startup.
 */
@StratumInternal
@Getter
@RequiredArgsConstructor
public enum WebErrorCode implements ErrorCode {

  /** The request broke validation rules; the body's {@code details} names each field. */
  INVALID_FIELDS("SX-WEB-1001", Severity.ERROR, "The request has invalid fields", ""),

  /** The request could not be read, such as a malformed body or a parameter of the wrong type. */
  UNREADABLE_REQUEST("SX-WEB-1002", Severity.ERROR, "The request could not be read", ""),

  /** No endpoint or file answers at the request's path. */
  NOT_FOUND("SX-WEB-1003", Severity.ERROR, "Nothing was found at this path", ""),

  /** The request failed with the status shown, for a reason other than the codes above. */
  REQUEST_FAILED("SX-WEB-1004", Severity.ERROR, "The request could not be completed", ""),

  /** The service failed unexpectedly; the cause is in its logs, under the trace ID. */
  INTERNAL_ERROR(
      "SX-WEB-1005",
      Severity.ERROR,
      "The service failed unexpectedly; quote the trace ID to its support team",
      ""),

  /** The client location database is set, but it is missing or not a MaxMind database. */
  CLIENT_LOCATION_DATABASE_UNREADABLE(
      "SX-WEB-1006",
      Severity.ERROR,
      "The client location database cannot be read as a MaxMind database",
      "Point the property at your GeoIP2 or GeoLite2 City or Country file, or remove it to log no"
          + " country or city."),

  /** The request ran out of time before its response started. */
  REQUEST_TIMED_OUT("SX-WEB-1007", Severity.ERROR, "The request ran out of time", ""),

  /** The request body is larger than the API accepts. */
  REQUEST_BODY_TOO_LARGE(
      "SX-WEB-1008", Severity.ERROR, "The request body is larger than this API accepts", "");

  /** The code, such as {@code SX-WEB-1001}. */
  private final String code;

  /** Whether the operation failed or only needs attention. */
  private final Severity severity;

  /** What went wrong, in fixed text. */
  private final String description;

  /** How to fix it, as the Fix column of the error codes page starts; empty for none. */
  private final String fix;
}
