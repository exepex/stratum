package com.exepex.stratum.observability;

import com.exepex.stratum.core.api.StratumInternal;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.Collection;
import java.util.Set;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Keeps only the allowed baggage entries: a caller's other entries are dropped when a call or
 * message is read, and the service's other entries are not sent. Trace headers pass unchanged.
 */
@StratumInternal
@RequiredArgsConstructor
public class AllowedBaggagePropagator implements TextMapPropagator {

  /** Spring Boot's propagator, which reads and writes the trace and baggage headers. */
  private final TextMapPropagator delegate;

  /** The baggage names that are kept, matched exactly. */
  private final Set<String> allowedNames;

  /**
   * Returns the headers the wrapped propagator reads and writes.
   *
   * @return the header names
   */
  @Override
  public Collection<String> fields() {
    return delegate.fields();
  }

  /**
   * Writes the trace headers and the allowed baggage entries into an outgoing call or message.
   *
   * @param context the context to send
   * @param carrier the outgoing call or message
   * @param setter writes one header
   * @param <C> the type of the carrier
   */
  @Override
  public <C> void inject(Context context, @Nullable C carrier, TextMapSetter<C> setter) {
    delegate.inject(withAllowedBaggage(context), carrier, setter);
  }

  /**
   * Reads the trace headers and the allowed baggage entries from an incoming call or message.
   *
   * @param context the context to add them to
   * @param carrier the incoming call or message
   * @param getter reads one header
   * @param <C> the type of the carrier
   * @return the context with the caller's trace and allowed baggage
   */
  @Override
  public <C> Context extract(Context context, @Nullable C carrier, TextMapGetter<C> getter) {
    return withAllowedBaggage(delegate.extract(context, carrier, getter));
  }

  /** Returns the context with only the allowed baggage entries, or the same context if all are. */
  private Context withAllowedBaggage(Context context) {
    var baggage = Baggage.fromContext(context);
    var droppedNames =
        baggage.asMap().keySet().stream().filter(Predicate.not(allowedNames::contains)).toList();
    if (droppedNames.isEmpty()) {
      return context;
    }
    var allowedBaggage = baggage.toBuilder();
    droppedNames.forEach(allowedBaggage::remove);
    return context.with(allowedBaggage.build());
  }
}
