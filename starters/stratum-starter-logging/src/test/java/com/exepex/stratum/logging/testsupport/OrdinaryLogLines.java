package com.exepex.stratum.logging.testsupport;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.logging.LoggingConstants;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import lombok.experimental.UtilityClass;
import org.springframework.boot.logging.StandardStackTracePrinter;

/**
 * The fields of an ordinary ECS log line, with made-up values and no personal data, for timing how
 * long masking one line takes.
 */
@UtilityClass
public class OrdinaryLogLines {

  /** Frames in each exception of {@link #STACK_TRACE}. */
  private static final int FRAMES_PER_EXCEPTION = 40;

  /** The source line of each exception's first frame; each later frame is one line further. */
  private static final int FIRST_FRAME_LINE = 100;

  /** The longest stack trace Boot prints for the line, as with the default settings. */
  private static final int STACK_TRACE_MAXIMUM_LENGTH = 16_384;

  /** A message of about 120 characters with an order number, a URL and a timestamp. */
  public static final String ORDINARY_MESSAGE =
      "Order orderId=42 placed with 3 items at 2026-11-03T10:15:30.123Z,"
          + " summary at https://orders.example.com/api/orders/42/summary";

  /** The service's own MDC fields, in the order they are written. */
  public static final List<Map.Entry<String, Object>> MDC_FIELDS =
      List.of(
          Map.entry("customerId", "cust-7f3a91"),
          Map.entry("tenantId", "tenant-eu-west"),
          Map.entry(LoggingContextConstants.TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736"));

  /** The ECS fields that hold no personal data, with typical values, in the order written. */
  public static final List<Map.Entry<String, Object>> SAFE_ECS_FIELDS =
      List.of(
          Map.entry("@timestamp", "2026-11-03T10:15:30.123Z"),
          Map.entry("log.level", "INFO"),
          Map.entry("log.logger", "com.example.orders.OrderService"),
          Map.entry("process.pid", 4242L),
          Map.entry("process.thread.name", "http-nio-8080-exec-7"),
          Map.entry("service.name", "orders"),
          Map.entry("service.version", "1.4.2"),
          Map.entry("service.environment", "production"),
          Map.entry("service.node.name", "orders-6d8f9c7b5-x2k4q"),
          Map.entry("ecs.version", "8.11"),
          Map.entry("error.type", "java.lang.IllegalStateException"),
          Map.entry(LoggingConstants.ERROR_CODE, "SX-ORDERS-1001"),
          Map.entry(LoggingContextConstants.TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736"),
          Map.entry(LoggingContextConstants.SPAN_ID, "00f067aa0ba902b7"),
          Map.entry(LoggingContextConstants.HTTP_METHOD, "POST"),
          Map.entry(LoggingContextConstants.HTTP_ROUTE, "/api/orders"),
          Map.entry(LoggingContextConstants.USER_AGENT, "Mozilla/5.0 (X11; Linux x86_64)"),
          Map.entry(LoggingContextConstants.DEVICE_CLASS, "Desktop"),
          Map.entry(LoggingContextConstants.DEVICE_NAME, "Linux Desktop"),
          Map.entry(LoggingContextConstants.OS_NAME, "Linux"),
          Map.entry(LoggingContextConstants.OS_VERSION, "6.8"),
          Map.entry(LoggingContextConstants.BROWSER_NAME, "Firefox"),
          Map.entry(LoggingContextConstants.BROWSER_VERSION, "132.0"));

  /** The error message of a line with a stack trace. */
  public static final String ERROR_MESSAGE = "Order 42 could not be placed";

  /**
   * An {@link IllegalStateException} caused by an {@link IOException}, each with 40 fixed frames,
   * printed root first as Spring Boot prints it: about 5KB.
   */
  public static final String STACK_TRACE =
      StandardStackTracePrinter.rootFirst()
          .withMaximumLength(STACK_TRACE_MAXIMUM_LENGTH)
          .printStackTraceToString(
              withFrames(
                  new IllegalStateException(
                      ERROR_MESSAGE,
                      withFrames(
                          new IOException("Payment service timed out"),
                          "com.example.orders.client.PaymentClient")),
                  "com.example.orders.service.OrderService"));

  /** Gives the exception fixed frames of the class, so the printed text is the same each run. */
  private static <T extends Throwable> T withFrames(T exception, String className) {
    exception.setStackTrace(
        IntStream.range(0, FRAMES_PER_EXCEPTION)
            .mapToObj(
                frame ->
                    new StackTraceElement(
                        className,
                        "step" + frame,
                        simpleFileName(className),
                        FIRST_FRAME_LINE + frame))
            .toArray(StackTraceElement[]::new));
    return exception;
  }

  /** Returns the source file name of the class, such as {@code OrderService.java}. */
  private static String simpleFileName(String className) {
    return className.substring(className.lastIndexOf('.') + 1) + ".java";
  }
}
