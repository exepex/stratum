package com.exepex.stratum.httpclient;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import lombok.Builder;

/**
 * The settings of one operation of a client: the calls of the interface methods marked {@link
 * StratumOperation} with its name. They win over the client's own.
 *
 * @param errors The service's own error code for each error status of this operation's answers,
 *     such as 404: ORD-0404. Each wins over the client's code for the same status. None by default.
 */
@Builder(toBuilder = true)
public record OperationSettings(
    Map<
            @Min(HttpClientConstants.MIN_ERROR_STATUS) @Max(HttpClientConstants.MAX_ERROR_STATUS)
            Integer,
            @Pattern(regexp = HttpClientConstants.ERROR_CODE_FORMAT) String>
        errors) {

  /** Keeps the codes a fixed map, empty when none is named. */
  public OperationSettings {
    errors = errors == null ? Map.of() : Map.copyOf(errors);
  }
}
