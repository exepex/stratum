package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.httpclient.ClientSettings;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * One client's own error codes for the error statuses of its answers, from its {@code errors}
 * settings: the operation's over the client's over the shared defaults. Built once per client, so a
 * call only looks a status up.
 */
@StratumInternal
public final class MappedErrorCodes {

  /** The client's codes, for a call of a method with no operation. */
  private final Map<Integer, ErrorCode> clientCodes;

  /** The codes of each operation, by its name, the client's included. */
  private final Map<String, Map<Integer, ErrorCode>> operationCodes;

  /**
   * Builds the codes of one client.
   *
   * @param settings the client's settings, merged over the shared defaults
   */
  public MappedErrorCodes(ClientSettings settings) {
    clientCodes = codesOf(settings.errorsOf(null));
    operationCodes =
        settings.operations().keySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    name -> name, name -> codesOf(settings.errorsOf(name))));
  }

  /**
   * Returns the service's own code for an error status of a call.
   *
   * @param status the answer's status
   * @param operation the call's operation, or {@code null} for a method with none
   * @return the code, or empty when the status is not mapped
   */
  public Optional<ErrorCode> codeFor(int status, @Nullable String operation) {
    var codes =
        operation == null ? clientCodes : operationCodes.getOrDefault(operation, clientCodes);
    return Optional.ofNullable(codes.get(status));
  }

  /** Returns each mapped status with its code as an error code. */
  private Map<Integer, ErrorCode> codesOf(Map<Integer, String> errors) {
    return errors.entrySet().stream()
        .collect(
            Collectors.toUnmodifiableMap(
                Map.Entry::getKey, mapped -> new MappedErrorCode(mapped.getValue())));
  }
}
