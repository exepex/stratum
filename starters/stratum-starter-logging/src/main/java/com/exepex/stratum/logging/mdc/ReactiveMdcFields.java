package com.exepex.stratum.logging.mdc;

import com.exepex.stratum.logging.LoggingConstants;
import java.util.HashMap;
import java.util.Map;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Adds MDC fields, such as {@code customerId}, to every log line a {@code Mono} or {@code Flux}
 * writes, on whichever thread its operators run. In reactive code {@code MDC.put} reaches only the
 * current thread, because Reactor sets each operator's logging context from the subscription; these
 * fields travel with the subscription instead. Fields of an enclosing call are kept; a field set
 * again takes the new value.
 */
@UtilityClass
public class ReactiveMdcFields {

  /**
   * Returns the {@code Mono} with the fields on every log line it writes.
   *
   * @param fields the MDC field names and values
   * @param work the work
   * @param <T> the result's type
   * @return the work, with the fields
   * @throws IllegalArgumentException if a field is one that tracing or workflows set
   */
  public static <T> Mono<T> mono(Map<String, String> fields, Mono<T> work) {
    requireServiceFieldNames(fields);
    return work.contextWrite(context -> withServiceFields(context, fields));
  }

  /**
   * Returns the {@code Flux} with the fields on every log line it writes.
   *
   * @param fields the MDC field names and values
   * @param work the work
   * @param <T> the elements' type
   * @return the work, with the fields
   * @throws IllegalArgumentException if a field is one that tracing or workflows set
   */
  public static <T> Flux<T> flux(Map<String, String> fields, Flux<T> work) {
    requireServiceFieldNames(fields);
    return work.contextWrite(context -> withServiceFields(context, fields));
  }

  /** Refuses the fields that tracing and workflows set, which would fall out of step. */
  private static void requireServiceFieldNames(Map<String, String> fields) {
    var managedField =
        fields.keySet().stream()
            .filter(ServiceMdcFieldsAccessor.FIELDS_MANAGED_ELSEWHERE::contains)
            .findFirst();
    if (managedField.isPresent()) {
      throw new IllegalArgumentException(
          "MDC field " + managedField.get() + " is set by tracing or workflows");
    }
  }

  /** Returns the context with the fields added to those of any enclosing call. */
  private static Context withServiceFields(Context context, Map<String, String> fields) {
    var serviceFields =
        new HashMap<>(
            context
                .<Map<String, String>>getOrEmpty(LoggingConstants.SERVICE_MDC_FIELDS)
                .orElseGet(Map::of));
    serviceFields.putAll(fields);
    return context.put(LoggingConstants.SERVICE_MDC_FIELDS, Map.copyOf(serviceFields));
  }
}
