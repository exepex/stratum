package com.exepex.stratum.observability.testsupport;

import static com.exepex.stratum.observability.testsupport.ObservabilityTestData.CORRELATION_ID;
import static com.exepex.stratum.observability.testsupport.ObservabilityTestData.HOSTILE_INPUT_LENGTH;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** Baggage headers a caller sends, and how to read which entries a propagator keeps. */
@UtilityClass
public class BaggageTestData {

  /** The W3C header that carries baggage. */
  public static final String BAGGAGE_HEADER = "baggage";

  /** The correlation ID a caller sends in baggage. */
  public static final String CORRELATION_ID_ENTRY =
      "correlationId=" + MessageTraceChecks.ORDER_CORRELATION_ID;

  /**
   * A caller's baggage: the correlation ID, a made-up secret and the correlation ID's name in
   * capitals, which only the first may pass.
   */
  public static final String CALLER_BAGGAGE =
      CORRELATION_ID_ENTRY + ",evil=SECRET,CORRELATIONID=leak";

  /** A baggage name that a service sends in a header of its own, through Spring Boot. */
  public static final String REMOTE_FIELD = "tenantId";

  /** Reads headers from a map, as a server reads a request's headers. */
  public static final TextMapGetter<Map<String, String>> HEADERS =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> headers) {
          return headers.keySet();
        }

        @Override
        public @Nullable String get(@Nullable Map<String, String> headers, String name) {
          return headers == null ? null : headers.get(name);
        }
      };

  /**
   * Returns the baggage names a propagator keeps when it reads this baggage header.
   *
   * @param propagator the propagator
   * @param baggageHeader the W3C baggage header
   * @return the kept names
   */
  public static Set<String> namesRead(TextMapPropagator propagator, String baggageHeader) {
    var context =
        propagator.extract(Context.root(), Map.of(BAGGAGE_HEADER, baggageHeader), HEADERS);
    return Baggage.fromContext(context).asMap().keySet();
  }

  /**
   * Returns the baggage names that W3C propagators, after this post-processor wraps them, keep when
   * they read this baggage header.
   *
   * @param allowList the post-processor
   * @param baggageHeader the W3C baggage header
   * @return the kept names
   */
  public static Set<String> namesKeptBy(BeanPostProcessor allowList, String baggageHeader) {
    var wrapped =
        (ContextPropagators)
            allowList.postProcessAfterInitialization(
                ContextPropagators.create(W3CBaggagePropagator.getInstance()), "propagators");
    return namesRead(wrapped.getTextMapPropagator(), baggageHeader);
  }

  /** OpenTelemetry's cap on a baggage header: 64 entries in 8,192 bytes. */
  private static final int CAPPED_ENTRIES = 64;

  /** The bytes of each entry in the capped header, so 64 of them fill 8,192 bytes. */
  private static final int CAPPED_ENTRY_LENGTH = 8_192 / CAPPED_ENTRIES;

  /**
   * Returns 64 entries in 8,192 bytes, the correlation ID first: each other entry, with its {@code
   * =} and {@code ,}, takes 128 bytes.
   *
   * @return the baggage at OpenTelemetry's cap
   */
  public static Baggage cappedBaggage() {
    var builder = Baggage.builder().put(LoggingContextConstants.CORRELATION_ID, CORRELATION_ID);
    IntStream.range(1, CAPPED_ENTRIES)
        .mapToObj(entry -> "e" + entry)
        .forEach(name -> builder.put(name, "v".repeat(CAPPED_ENTRY_LENGTH - name.length() - 2)));
    return builder.build();
  }

  /**
   * Returns a 1MB baggage header of short entries, the correlation ID first.
   *
   * @return the header
   */
  public static String hugeHeader() {
    var header = new StringBuilder(CORRELATION_ID_ENTRY);
    IntStream.iterate(0, entry -> header.length() < HOSTILE_INPUT_LENGTH, entry -> entry + 1)
        .forEach(entry -> header.append(",e").append(entry).append("=v"));
    return header.toString();
  }
}
