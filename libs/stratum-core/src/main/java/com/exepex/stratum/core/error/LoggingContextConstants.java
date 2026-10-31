package com.exepex.stratum.core.error;

import java.util.List;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The keys under which tracing and Stratum put request IDs in the logging context (SLF4J's MDC).
 */
@UtilityClass
public class LoggingContextConstants {

  /** The trace ID of the request, set by tracing. */
  public static final String TRACE_ID = "traceId";

  /** The span ID of the operation, set by tracing. */
  public static final String SPAN_ID = "spanId";

  /** The ID that ties the steps of one business workflow together, set by Stratum. */
  public static final String CORRELATION_ID = "correlationId";

  /** The request's HTTP method, such as {@code GET}, set by the web module. */
  public static final String HTTP_METHOD = "httpMethod";

  /** The route the request matched, such as {@code /orders/{orderId}}, set by the web module. */
  public static final String HTTP_ROUTE = "httpRoute";

  /** The request's path, such as {@code /orders/42}, set by the web module. */
  public static final String HTTP_PATH = "httpPath";

  /** The request's query string, without the {@code ?}, set by the web module. */
  public static final String HTTP_QUERY = "httpQuery";

  /** The IP address of the client that sent the request, set by the web module. */
  public static final String CLIENT_IP = "clientIp";

  /** The country of the client's IP address, as an ISO code, set by the web module. */
  public static final String CLIENT_COUNTRY = "clientCountry";

  /** The city of the client's IP address, set by the web module. */
  public static final String CLIENT_CITY = "clientCity";

  /** The {@code User-Agent} header as the client sent it, set by the web module. */
  public static final String USER_AGENT = "userAgent";

  /** The kind of device, such as {@code Phone} or {@code Desktop}, set by the web module. */
  public static final String DEVICE_CLASS = "deviceClass";

  /** The device's brand and model, such as {@code Google Pixel 7}, set by the web module. */
  public static final String DEVICE_NAME = "deviceName";

  /** The operating system, such as {@code Android}, set by the web module. */
  public static final String OS_NAME = "osName";

  /** The operating system's version, set by the web module. */
  public static final String OS_VERSION = "osVersion";

  /** The browser or other client program, such as {@code Chrome}, set by the web module. */
  public static final String BROWSER_NAME = "browserName";

  /** The browser's major version, set by the web module. */
  public static final String BROWSER_VERSION = "browserVersion";

  /**
   * The fields the web module puts in the logging context for each request and keeps in step with
   * it on every thread the request's work runs on.
   */
  public static final List<String> WEB_REQUEST_FIELDS =
      List.of(
          HTTP_METHOD,
          HTTP_ROUTE,
          HTTP_PATH,
          HTTP_QUERY,
          CLIENT_IP,
          CLIENT_COUNTRY,
          CLIENT_CITY,
          USER_AGENT,
          DEVICE_CLASS,
          DEVICE_NAME,
          OS_NAME,
          OS_VERSION,
          BROWSER_NAME,
          BROWSER_VERSION);

  /**
   * The format a trace, span or correlation ID must have before Stratum writes it to a log line or
   * an error response: 1 to 128 letters, digits, {@code -}, {@code _}, {@code .} or {@code :}. An
   * ID in any other format, such as one with a line break sent by a caller, could forge a log line
   * or break a response, so Stratum leaves it out.
   */
  private static final Pattern ID_FORMAT_SAFE_FOR_LOGS = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

  /**
   * Returns whether a trace, span or correlation ID, or a name used like one, has the format that
   * is safe to write to a log line or an error response: 1 to 128 letters, digits, {@code -},
   * {@code _}, {@code .} or {@code :}.
   *
   * @param idOrName the ID or name, or null when it is not set
   * @return true if it is set and safe for logs
   */
  public static boolean isSafeForLogs(@Nullable String idOrName) {
    return idOrName != null && ID_FORMAT_SAFE_FOR_LOGS.matcher(idOrName).matches();
  }
}
