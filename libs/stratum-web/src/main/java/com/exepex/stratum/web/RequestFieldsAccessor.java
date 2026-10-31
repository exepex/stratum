package com.exepex.stratum.web;

import com.exepex.stratum.core.error.LoggingContextConstants;
import io.micrometer.context.ThreadLocalAccessor;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * Puts a request's fields in the logging context (SLF4J's MDC) and reads them back, so that
 * Spring's executors and Reactor carry them to every thread the request's work runs on, and remove
 * them when that work ends. Registered with Micrometer's context propagation while the web module
 * is on.
 *
 * <p>Reactor calls it around every operator, so it writes a field only when its value changes.
 */
public class RequestFieldsAccessor implements ThreadLocalAccessor<RequestFieldValues> {

  /** Returns no route: on a thread, the route is in the logging context once it is known. */
  private static final Supplier<@Nullable String> NO_ROUTE = () -> null;

  /**
   * Returns the key under which the fields are carried.
   *
   * @return {@value WebConstants#REQUEST_FIELDS_CONTEXT_KEY}
   */
  @Override
  public Object key() {
    return WebConstants.REQUEST_FIELDS_CONTEXT_KEY;
  }

  /**
   * Returns the request fields on this thread, or null when it holds none.
   *
   * @return the fields, the route included
   */
  @Override
  public @Nullable RequestFieldValues getValue() {
    var fields =
        LoggingContextConstants.WEB_REQUEST_FIELDS.stream()
            .filter(name -> MDC.get(name) != null)
            .collect(Collectors.toMap(name -> name, MDC::get));
    return fields.isEmpty() ? null : new RequestFieldValues(fields, NO_ROUTE);
  }

  /**
   * Puts the carried fields in the logging context, writing only those that differ, and removes any
   * request field they do not hold.
   *
   * @param carriedFields the fields of the request whose work runs on this thread
   */
  @Override
  public void setValue(RequestFieldValues carriedFields) {
    LoggingContextConstants.WEB_REQUEST_FIELDS.forEach(
        name -> putIfChanged(name, valueOf(carriedFields, name)));
  }

  /** Removes every request field from the logging context. */
  @Override
  public void setValue() {
    LoggingContextConstants.WEB_REQUEST_FIELDS.forEach(name -> putIfChanged(name, null));
  }

  /** Returns the value of one field: the route comes from the request once it is matched. */
  private @Nullable String valueOf(RequestFieldValues carriedFields, String name) {
    var value = carriedFields.fields().get(name);
    return value == null && LoggingContextConstants.HTTP_ROUTE.equals(name)
        ? carriedFields.route().get()
        : value;
  }

  /** Sets or removes one field, unless it already holds that value. */
  private void putIfChanged(String name, @Nullable String value) {
    if (Objects.equals(MDC.get(name), value)) {
      return;
    }
    if (value == null) {
      MDC.remove(name);
    } else {
      MDC.put(name, value);
    }
  }
}
