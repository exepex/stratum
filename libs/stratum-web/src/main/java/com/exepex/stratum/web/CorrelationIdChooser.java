package com.exepex.stratum.web;

import com.exepex.stratum.core.error.LoggingContextConstants;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Chooses a request's correlation ID: the caller's {@code X-Correlation-Id} header, then the {@code
 * correlationId} the caller sent in the tracing baggage, then the trace ID. A caller's value counts
 * only when it is 1 to 128 letters, digits, {@code -}, {@code _}, {@code .} or {@code :}, and only
 * while {@code stratum.web.correlation.accept-from-caller} is true.
 */
public class CorrelationIdChooser {

  /** Whether the caller's values are used. */
  private final boolean acceptFromCaller;

  /**
   * Makes the chooser.
   *
   * @param settings the web settings, which say whether the caller's values are used
   */
  public CorrelationIdChooser(WebProperties settings) {
    this.acceptFromCaller = settings.correlation().acceptFromCaller();
  }

  /**
   * Returns the request's correlation ID, or nothing when the request has none, such as without
   * tracing.
   *
   * @param header the caller's {@code X-Correlation-Id} header, if sent
   * @param baggage the {@code correlationId} in the caller's tracing baggage, if sent
   * @param traceId the request's trace ID, if it is traced
   * @return the ID, and whether it must be put in the baggage
   */
  public Optional<Choice> choose(
      @Nullable String header, @Nullable String baggage, @Nullable String traceId) {
    var callerValues = acceptFromCaller ? Stream.of(header, baggage) : Stream.<String>empty();
    return Stream.concat(callerValues, Stream.of(traceId))
        .filter(LoggingContextConstants::isSafeForLogs)
        .findFirst()
        .map(
            correlationId ->
                new Choice(correlationId, needsBaggage(correlationId, baggage, traceId)));
  }

  /**
   * Returns whether the baggage must change to carry the ID. Without a baggage value, the trace ID
   * is the correlation ID already, here and in every service called, so it is not added.
   */
  private boolean needsBaggage(
      String correlationId, @Nullable String baggage, @Nullable String traceId) {
    return baggage == null ? !correlationId.equals(traceId) : !correlationId.equals(baggage);
  }

  /**
   * A request's correlation ID.
   *
   * @param correlationId the ID, which goes in the logs and the {@code X-Correlation-Id} response
   *     header
   * @param setInBaggage whether it must be put in the tracing baggage, so the logs and every
   *     service called get it
   */
  public record Choice(String correlationId, boolean setInBaggage) {}
}
