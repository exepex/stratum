package com.exepex.stratum.httpclient;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The settings of one operation of a client: the calls of the interface methods marked {@link
 * StratumOperation} with its name. They win over the client's own.
 *
 * @param errors The service's own error code for each error status of this operation's answers,
 *     such as 404: ORD-0404. Each wins over the client's code for the same status. None by default.
 * @param readTimeout The longest wait for the first byte of this operation's answers and between
 *     two of their bytes, such as 20s for a slow report. Unset, the client's read-timeout applies.
 * @param totalTimeout The time allowed for a whole call of this operation, retries, waits and the
 *     answer included, such as 30s. Unset, the client's total-timeout applies.
 */
@Builder(toBuilder = true)
public record OperationSettings(
    Map<
            @Min(HttpClientConstants.MIN_ERROR_STATUS) @Max(HttpClientConstants.MAX_ERROR_STATUS)
            Integer,
            @Pattern(regexp = HttpClientConstants.ERROR_CODE_FORMAT) String>
        errors,
    @Nullable Duration readTimeout,
    @Nullable Duration totalTimeout) {

  /** Keeps the codes a fixed map, empty when none is named. */
  public OperationSettings {
    errors = errors == null ? Map.of() : Map.copyOf(errors);
  }

  /**
   * Returns these settings, with each timeout left unset taken from the client.
   *
   * @param client the client's settings, with the shared defaults already merged in
   * @return the merged settings
   */
  public OperationSettings orElse(ClientSettings client) {
    return toBuilder()
        .readTimeout(Objects.requireNonNullElse(readTimeout, client.readTimeout()))
        .totalTimeout(Objects.requireNonNullElse(totalTimeout, client.totalTimeout()))
        .build();
  }

  /**
   * Returns the timeouts that are set.
   *
   * @return the set timeouts
   */
  Stream<Duration> durations() {
    return Stream.of(readTimeout, totalTimeout).filter(Objects::nonNull);
  }
}
