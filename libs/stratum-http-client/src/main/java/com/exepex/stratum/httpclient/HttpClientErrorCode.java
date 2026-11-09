package com.exepex.stratum.httpclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The codes of the HTTP client module, each listed with its fix in {@code
 * docs/guides/error-codes.md}. A code from {@code SX-HTTPC-1001} stops the application at startup;
 * one below {@code SX-HTTPC-1000} ends a call to another service, and its HTTP status is a default
 * of {@value HttpClientConstants#WEB_ERROR_STATUSES}.
 */
@StratumInternal
@Getter
@RequiredArgsConstructor
public enum HttpClientErrorCode implements ErrorCode {

  /** The other service did not answer in time; the caller gets 504. */
  DOWNSTREAM_TIMEOUT(
      "SX-HTTPC-0001", Severity.ERROR, "The other service did not answer in time", ""),

  /** The other service cannot be reached, or the connection pool is full; the caller gets 503. */
  DOWNSTREAM_UNAVAILABLE(
      "SX-HTTPC-0002", Severity.ERROR, "The other service cannot be reached", ""),

  /** The client's circuit breaker is open, so the call was not sent; the caller gets 503. */
  BREAKER_OPEN(
      "SX-HTTPC-0003",
      Severity.ERROR,
      "The other service is failing, so the call was not sent while its circuit breaker is open",
      ""),

  /**
   * A Spring Boot setting of an HTTP service group is set for a Stratum client, which ignores it.
   */
  BOOT_CLIENT_SETTING(
      "SX-HTTPC-1001",
      Severity.ERROR,
      "A Spring Boot setting under "
          + HttpClientConstants.BOOT_SERVICE_CLIENTS
          + ".<name> is set for a Stratum client, which does not read it",
      "Move the setting to "
          + HttpClientConstants.CLIENTS
          + ".<name>, such as url instead of base-url and timeout instead of read-timeout."),

  /** A Stratum client returns {@code Mono} or {@code Flux}, which the blocking starter cannot. */
  REACTIVE_METHOD(
      "SX-HTTPC-1002",
      Severity.ERROR,
      "A Stratum client's method returns Mono or Flux, and the blocking HTTP client starter cannot"
          + " send it",
      "Return the value itself, such as Stock instead of Mono<Stock>; the reactive HTTP client"
          + " starter is planned."),

  /** A client's name is not lowercase letters, digits and hyphens, from a letter. */
  CLIENT_NAME_INVALID(
      "SX-HTTPC-1003",
      Severity.ERROR,
      "A @StratumClient name is not lowercase letters, digits and hyphens, starting with a letter",
      "Rename the client, such as inventory or payments-v2, and its settings with it."),

  /** A Stratum client has no address to call. */
  URL_MISSING(
      "SX-HTTPC-1004",
      Severity.ERROR,
      "A Stratum client has no address",
      "Set " + HttpClientConstants.CLIENTS + ".<name>.url, such as http://inventory:8080."),

  /** A client's {@code interfaces} names a type that is not an interface on the classpath. */
  INTERFACE_NOT_FOUND(
      "SX-HTTPC-1007",
      Severity.ERROR,
      "A client's interfaces setting names no interface on the classpath",
      "Name an interface by its full name, such as com.partner.OrdersApi, and check that its jar"
          + " is on the classpath."),

  /** An interface is marked for one client and named in another client's {@code interfaces}. */
  INTERFACE_TWO_CLIENTS(
      "SX-HTTPC-1008",
      Severity.ERROR,
      "An interface is marked @StratumClient for one client and named in another client's"
          + " interfaces setting",
      "Keep the interface in one client: remove it from the other client's interfaces, or change"
          + " its @StratumClient name.");

  /** The code, such as {@code SX-HTTPC-1001}. */
  private final String code;

  /** Whether the code is an error or a warning. */
  private final Severity severity;

  /** What went wrong, in one sentence that names no value. */
  private final String description;

  /** How to fix it. */
  private final String fix;
}
