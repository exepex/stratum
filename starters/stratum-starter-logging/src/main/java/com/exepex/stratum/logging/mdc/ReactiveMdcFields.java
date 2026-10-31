package com.exepex.stratum.logging.mdc;

import com.exepex.stratum.logging.LoggingConstants;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import java.util.HashMap;
import java.util.Map;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Adds MDC fields, such as {@code customerId}, to every log line a {@code Mono} or {@code Flux}
 * writes, on whichever thread its operators run. A field put with {@code MDC.put} follows Reactor's
 * own thread changes, but after a step that completes outside Reactor, such as a {@code
 * CompletableFuture}, Reactor resets the MDC from the subscription. These fields travel with the
 * subscription instead, together with the fields the subscribing thread holds. Fields of an
 * enclosing call are kept; a field set again takes the new value.
 */
@UtilityClass
public class ReactiveMdcFields {

  /** Reads the service's own MDC fields on the subscribing thread. */
  private static final ServiceMdcFieldsAccessor SERVICE_MDC_FIELDS_ACCESSOR =
      new ServiceMdcFieldsAccessor();

  /**
   * Returns the {@code Mono} with the fields on every log line it writes.
   *
   * @param fields the MDC field names and values
   * @param monoThatLogs the {@code Mono} whose log lines get the fields
   * @param <T> the result's type
   * @return the {@code Mono}, with the fields
   * @throws IllegalArgumentException if a field is one that tracing, workflows or the web module
   *     set
   */
  public static <T> Mono<T> mono(Map<String, String> fields, Mono<T> monoThatLogs) {
    requireServiceFieldNames(fields);
    return monoThatLogs.contextWrite(context -> withServiceFields(context, fields));
  }

  /**
   * Returns the {@code Flux} with the fields on every log line it writes.
   *
   * @param fields the MDC field names and values
   * @param fluxThatLogs the {@code Flux} whose log lines get the fields
   * @param <T> the elements' type
   * @return the {@code Flux}, with the fields
   * @throws IllegalArgumentException if a field is one that tracing, workflows or the web module
   *     set
   */
  public static <T> Flux<T> flux(Map<String, String> fields, Flux<T> fluxThatLogs) {
    requireServiceFieldNames(fields);
    return fluxThatLogs.contextWrite(context -> withServiceFields(context, fields));
  }

  /**
   * Refuses the fields that tracing, workflows and the web module set, which would fall out of
   * step.
   */
  private static void requireServiceFieldNames(Map<String, String> fields) {
    var managedField =
        fields.keySet().stream()
            .filter(ServiceMdcFieldsAccessor.FIELDS_MANAGED_ELSEWHERE::contains)
            .findFirst();
    if (managedField.isPresent()) {
      throw new IllegalArgumentException(
          "MDC field " + managedField.get() + " is set by tracing, workflows or the web module");
    }
  }

  /**
   * Returns the context with the fields added to those the subscribing thread holds, which include
   * any put with {@code MDC.put}, and to those of any enclosing call. They also go in the whole
   * logging context that a service may carry in Reactor, so that restoring it keeps them all.
   */
  private static Context withServiceFields(Context context, Map<String, String> fields) {
    var serviceFields = new HashMap<>(SERVICE_MDC_FIELDS_ACCESSOR.serviceFieldsOnThisThread());
    serviceFields.putAll(
        context
            .<Map<String, String>>getOrEmpty(LoggingConstants.SERVICE_MDC_FIELDS)
            .orElseGet(Map::of));
    serviceFields.putAll(fields);
    var wholeLoggingContext =
        new HashMap<>(
            context
                .<Map<String, String>>getOrEmpty(Slf4jThreadLocalAccessor.KEY)
                .orElseGet(Map::of));
    wholeLoggingContext.putAll(serviceFields);
    return context
        .put(LoggingConstants.SERVICE_MDC_FIELDS, Map.copyOf(serviceFields))
        .put(Slf4jThreadLocalAccessor.KEY, Map.copyOf(wholeLoggingContext));
  }
}
