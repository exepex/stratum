package com.exepex.stratum.web;

import com.exepex.stratum.core.error.LoggingContextConstants;
import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.web.util.UriUtils;

/**
 * Chooses a request's correlation ID: the caller's {@code X-Correlation-Id} header, then the {@code
 * correlationId} the caller sent in the tracing baggage, then the trace ID. A caller's value counts
 * only when it is 1 to 128 letters, digits, {@code -}, {@code _}, {@code .} or {@code :}, and only
 * while {@code stratum.web.correlation.accept-from-caller} is true.
 *
 * <p>Both web stacks use it the same way. Before tracing starts, they rewrite the request's {@code
 * baggage} header with {@link #rewrittenBaggage}, so tracing, the logs and every service called get
 * the caller's ID. Once tracing has started, they return {@link #currentCorrelationId} in the
 * response header.
 */
public class CorrelationIdChooser {

  /** Separates the entries of a baggage header. */
  private static final String ENTRY_SEPARATOR = ",";

  /** Separates an entry's key from its value. */
  private static final String KEY_VALUE_SEPARATOR = "=";

  /** Starts an entry's properties, which follow its value. */
  private static final String PROPERTIES_SEPARATOR = ";";

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
   * Returns the baggage entries the request must carry so that tracing takes the caller's ID: the
   * caller's other entries, and one {@code correlationId} with the caller's usable ID, if any.
   * Returns nothing when the request's baggage is right as it is.
   *
   * @param correlationHeader the caller's {@code X-Correlation-Id} header, if sent
   * @param baggageHeaders the request's {@code baggage} headers, each with one or more entries
   * @return the new entries, possibly none, or nothing when the baggage needs no change
   */
  public Optional<List<String>> rewrittenBaggage(
      @Nullable String correlationHeader, List<String> baggageHeaders) {
    var entries =
        baggageHeaders.stream()
            .flatMap(header -> Arrays.stream(header.split(ENTRY_SEPARATOR)))
            .map(String::strip)
            .filter(entry -> !entry.isEmpty())
            .toList();
    var callerBaggageIds =
        entries.stream().flatMap(entry -> correlationIdOf(entry).stream()).toList();
    var callerBaggageId = callerBaggageIds.stream().findFirst();
    var callerId = callerIdOf(correlationHeader, callerBaggageId.orElse(null));
    // Tracing keeps the last of several correlationId entries, which was not checked, so the
    // baggage is kept only with no entry, or with one that is already the chosen ID.
    var baggageAlreadyRight = callerBaggageIds.size() <= 1 && callerId.equals(callerBaggageId);
    if (baggageAlreadyRight) {
      return Optional.empty();
    }
    var otherEntries = entries.stream().filter(entry -> correlationIdOf(entry).isEmpty());
    var callerEntry =
        callerId.map(id -> LoggingContextConstants.CORRELATION_ID + KEY_VALUE_SEPARATOR + id);
    return Optional.of(Stream.concat(otherEntries, callerEntry.stream()).toList());
  }

  /**
   * Returns the current request's correlation ID, once tracing has started: the {@code
   * correlationId} in its baggage, which {@link #rewrittenBaggage} has set to the caller's, or else
   * the trace ID.
   *
   * @param tracer the tracer, in the request's tracing context
   * @return the ID, or nothing when the request is not traced, as with the no-op tracer
   */
  public Optional<String> currentCorrelationId(Tracer tracer) {
    return Optional.ofNullable(tracer.getBaggage(LoggingContextConstants.CORRELATION_ID))
        .map(Baggage::get)
        .or(
            () ->
                Optional.ofNullable(tracer.currentSpan())
                    .map(Span::context)
                    .map(TraceContext::traceId)
                    .filter(Predicate.not(String::isEmpty)));
  }

  /** Returns the caller's ID to use: the header, then the baggage value, when usable. */
  private Optional<String> callerIdOf(@Nullable String header, @Nullable String baggage) {
    return acceptFromCaller
        ? Stream.of(header, baggage).filter(LoggingContextConstants::isSafeForLogs).findFirst()
        : Optional.empty();
  }

  /**
   * Returns the decoded value of a baggage entry whose key is {@code correlationId}, if it is one.
   * Baggage values may be percent-encoded, as some tracing libraries send {@code checkout%3A777}.
   */
  private Optional<String> correlationIdOf(String entry) {
    var keyAndValue = entry.split(PROPERTIES_SEPARATOR, 2)[0].split(KEY_VALUE_SEPARATOR, 2);
    return keyAndValue.length == 2
            && LoggingContextConstants.CORRELATION_ID.equals(keyAndValue[0].strip())
        ? Optional.of(decoded(keyAndValue[1].strip()))
        : Optional.empty();
  }

  /**
   * Returns a baggage value percent-decoded, or as sent when an escape is broken, so the format
   * check rejects it.
   */
  private String decoded(String value) {
    try {
      return UriUtils.decode(value, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException brokenEscape) {
      return value;
    }
  }
}
