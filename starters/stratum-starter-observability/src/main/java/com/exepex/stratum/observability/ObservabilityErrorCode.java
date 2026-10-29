package com.exepex.stratum.observability;

import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The codes of the observability module, each listed with its fix in {@code
 * docs/guides/error-codes.md}. Every error here stops the application at startup.
 */
@Getter
@RequiredArgsConstructor
public enum ObservabilityErrorCode implements ErrorCode {

  /**
   * With the module on, metrics or spans would be sent nowhere useful because their address is not
   * set. The detail names each missing property.
   */
  EXPORT_ENDPOINT_MISSING(
      "SX-OBSERVABILITY-1001",
      Severity.ERROR,
      "An OTLP export address is not set, so metrics or traces would be lost"),

  /**
   * A metric was dropped because one of its tags is named like an ID, whose values would make a new
   * time series each. The detail names the metric and the tag, never a value.
   */
  METRIC_WITH_ID_TAG_DROPPED(
      "SX-OBSERVABILITY-1002",
      Severity.WARNING,
      "A metric was dropped because a tag holds an ID; put IDs in logs or span attributes");

  /** The code, such as {@code SX-OBSERVABILITY-1001}. */
  private final String code;

  /** Whether the operation failed or only needs attention. */
  private final Severity severity;

  /** What went wrong, in fixed text. */
  private final String description;
}
