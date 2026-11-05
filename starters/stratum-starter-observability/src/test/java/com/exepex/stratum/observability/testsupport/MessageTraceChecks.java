package com.exepex.stratum.observability.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Sends a message inside a trace and checks that its receiver joined that trace and that the send
 * and the handling were timed.
 */
@RequiredArgsConstructor
public class MessageTraceChecks {

  /** Spring's timer of each sent JMS message. */
  public static final String JMS_PUBLISH_TIMER = "jms.message.publish";

  /** Spring's timer of each handled JMS message. */
  public static final String JMS_PROCESS_TIMER = "jms.message.process";

  /** The correlation ID the sender puts in baggage. */
  private static final String ORDER_CORRELATION_ID = "order-42-c7d1";

  /** The message sent in each test. */
  private static final String ORDER_PLACED = "order-placed";

  /** The MDC key in which Spring Boot puts the trace ID. */
  private static final String TRACE_ID = "traceId";

  /** Starts and ends the sender's span. */
  private final Tracer tracer;

  /** Holds the timers of the senders and receivers. */
  private final MeterRegistry meterRegistry;

  /**
   * Sends a message inside a span whose baggage holds the correlation ID.
   *
   * @param sender sends the message it is given
   * @return the span's trace ID
   */
  public String sendInTrace(Consumer<String> sender) {
    var span = tracer.nextSpan().name("place-order").start();
    try (var _ = tracer.withSpan(span);
        var _ =
            tracer.createBaggageInScope(
                LoggingContextConstants.CORRELATION_ID, ORDER_CORRELATION_ID)) {
      sender.accept(ORDER_PLACED);
      return span.context().traceId();
    } finally {
      span.end();
    }
  }

  /**
   * Checks that the receiver logged with the sender's trace ID and correlation ID.
   *
   * @param loggingContext the receiver's logging context
   * @param traceId the sender's trace ID
   */
  public void assertReceivedInSameTrace(
      @Nullable Map<String, String> loggingContext, String traceId) {
    assertThat(loggingContext)
        .containsEntry(TRACE_ID, traceId)
        .containsEntry(LoggingContextConstants.CORRELATION_ID, ORDER_CORRELATION_ID);
  }

  /**
   * Returns how many durations the timer recorded, or 0 before it exists.
   *
   * @param timerName the timer's name
   * @return its count
   */
  public long timerCount(String timerName) {
    return Optional.ofNullable(meterRegistry.find(timerName).timer()).map(Timer::count).orElse(0L);
  }

  /**
   * Waits until the timer has recorded this many durations; a receiver's timer stops just after the
   * message is handled.
   *
   * @param timerName the timer's name
   * @param expectedCount the count to wait for
   */
  public void assertTimerCount(String timerName, long expectedCount) {
    await()
        .untilAsserted(
            () -> assertThat(timerCount(timerName)).as(timerName).isEqualTo(expectedCount));
  }
}
