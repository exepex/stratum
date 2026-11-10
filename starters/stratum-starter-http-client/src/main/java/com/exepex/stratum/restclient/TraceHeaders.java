package com.exepex.stratum.restclient;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.httpclient.HttpClientConstants;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;

/**
 * A client's {@code trace-headers}, applied to each attempt when it is sent, after the copied
 * headers and the interceptors. With {@code true}, the call gets {@code X-Correlation-Id} from the
 * logging context when it has none and the ID is safe for logs; with {@code false}, every trace,
 * baggage and correlation header is removed, whoever set it, along with the baggage entries Spring
 * Boot sends as headers of their own.
 */
@RequiredArgsConstructor
final class TraceHeaders {

  /** Whether the client sends the correlation ID, or strips the trace headers. */
  private final boolean sent;

  /** The headers removed when the client sends no trace headers. */
  private final List<String> stripped;

  /**
   * Returns a client's trace headers step, with the names of Spring Boot's {@code
   * management.tracing.baggage.remote-fields} read once, at startup.
   *
   * @param sent the client's {@code trace-headers}
   * @param environment the application's environment
   * @return the step
   */
  static TraceHeaders of(boolean sent, Environment environment) {
    var remoteFields =
        Binder.get(environment)
            .bind(HttpClientConstants.BAGGAGE_REMOTE_FIELDS, Bindable.listOf(String.class))
            .orElseGet(List::of);
    return new TraceHeaders(
        sent,
        Stream.concat(HttpClientConstants.TRACE_HEADERS.stream(), remoteFields.stream()).toList());
  }

  /**
   * Adds the correlation ID to an attempt's headers, or removes every trace header from them.
   *
   * @param headers the attempt's headers
   */
  void applyTo(HttpHeaders headers) {
    if (sent) {
      addCorrelationId(headers);
    } else {
      stripped.forEach(headers::remove);
    }
  }

  /** Sets the logging context's correlation ID, unless the call already has one or it is unsafe. */
  private void addCorrelationId(HttpHeaders headers) {
    if (headers.containsHeader(LoggingContextConstants.CORRELATION_ID_HEADER)) {
      return;
    }
    var correlationId = MDC.get(LoggingContextConstants.CORRELATION_ID);
    if (LoggingContextConstants.isSafeForLogs(correlationId)) {
      headers.set(LoggingContextConstants.CORRELATION_ID_HEADER, correlationId);
    }
  }
}
