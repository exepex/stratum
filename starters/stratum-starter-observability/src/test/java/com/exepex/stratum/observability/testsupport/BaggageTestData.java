package com.exepex.stratum.observability.testsupport;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.util.Map;
import java.util.Set;
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
}
