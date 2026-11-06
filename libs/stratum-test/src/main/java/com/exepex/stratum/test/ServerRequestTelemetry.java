package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.core.api.StratumInternal;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Keeps every span a web service under test exports, after Stratum removed what a span must not
 * send, and checks what a request recorded on its {@code http.server.requests} timer and its server
 * span, so both web stacks are checked against the same expectations. A test adds it with
 * {@code @Import(ServerRequestTelemetry.class)}, with span export on and OTLP off.
 */
@StratumInternal
@RequiredArgsConstructor
public class ServerRequestTelemetry implements SpanExporter {

  /** What a request that fails with a {@code StratumException} records. */
  public static final Recorded DUPLICATE_ORDER =
      Recorded.builder()
          .path("/orders/duplicate")
          .exception("StratumException")
          .code(Recorded.ORDER_CODE)
          .failed(true)
          .build();

  /** An order whose body is over the default limit, which both stacks refuse with a 413. */
  public static final Recorded TOO_LARGE_ORDER =
      Recorded.builder()
          .path("/orders")
          .exception("RequestBodyTooLargeException")
          .code(Recorded.NONE)
          .failed(true)
          .build();

  /** What a request with each tested outcome records, on both web stacks' orders applications. */
  public static final List<Recorded> ORDERS_CASES =
      List.of(
          Recorded.builder()
              .path("/orders/42")
              .uri("/orders/{orderId}")
              .exception(Recorded.NONE)
              .code(Recorded.NONE)
              .failed(false)
              .build(),
          DUPLICATE_ORDER,
          Recorded.builder()
              .path("/orders/duplicate-existing")
              .exception("ExistingOrderException")
              .code(Recorded.ORDER_CODE)
              .failed(true)
              .build(),
          Recorded.builder()
              .path("/orders/duplicate-wrapped")
              .exception("IllegalStateException")
              .code(Recorded.ORDER_CODE)
              .failed(true)
              .build(),
          Recorded.builder()
              .path("/orders/duplicate-with-status/418")
              .uri("/orders/duplicate-with-status/{status}")
              .exception("ResponseStatusException")
              .code(Recorded.ORDER_CODE)
              .failed(true)
              .build(),
          Recorded.builder()
              .path("/orders/broken")
              .exception("IllegalStateException")
              .code(Recorded.NONE)
              .failed(true)
              .build(),
          Recorded.builder()
              .path("/no-such-path")
              .uri("/**")
              .exception("NoResourceFoundException")
              .code(Recorded.NONE)
              .failed(true)
              .build(),
          Recorded.builder()
              .path("/orders/refund")
              .exception(Recorded.NONE)
              .code(Recorded.NONE)
              .failed(false)
              .build());

  /** The span attribute that holds the request's path. */
  private static final AttributeKey<String> URL = AttributeKey.stringKey("http.url");

  /** The attribute and tag that hold the request's error code. */
  private static final String ERROR_CODE = "error.code";

  /** How long a check waits for the request's span to end and be exported. */
  private static final Duration WAIT = Duration.ofSeconds(10);

  /** The spans exported so far, oldest first. */
  private final List<SpanData> spans = new CopyOnWriteArrayList<>();

  /** Spring Boot's tracer provider, read late because it is built from this exporter. */
  private final ObjectProvider<SdkTracerProvider> tracerProvider;

  /** The service's meters. */
  private final ObjectProvider<MeterRegistry> meterRegistry;

  /**
   * Checks the timer and the span of the last request to the path: the timer has the expected
   * {@code error}, {@code exception} and {@code error.code} tags, and the span the expected status
   * and the code only when there is one.
   *
   * @param expected what the request recorded
   */
  public void check(Recorded expected) {
    tracerProvider.getObject().forceFlush().join(WAIT.toSeconds(), TimeUnit.SECONDS);
    var span =
        await().atMost(WAIT).until(() -> lastServerSpanOf(expected.path()), Optional::isPresent);
    assertThat(span.orElseThrow())
        .as("server span of %s", expected.path())
        .satisfies(
            serverSpan ->
                assertThat(serverSpan.getStatus().getStatusCode())
                    .isEqualTo(expected.failed() ? StatusCode.ERROR : StatusCode.UNSET),
            serverSpan ->
                assertThat(serverSpan.getAttributes().get(AttributeKey.stringKey(ERROR_CODE)))
                    .isEqualTo(Recorded.NONE.equals(expected.code()) ? null : expected.code()));
    assertThat(
            meterRegistry
                .getObject()
                .find(WebTestSupport.SERVER_REQUESTS_TIMER)
                .tags(
                    "uri",
                    expected.uri(),
                    "error",
                    expected.exception(),
                    "exception",
                    expected.exception(),
                    ERROR_CODE,
                    expected.code())
                .timer())
        .as("timer of %s, %s", expected.path(), expected)
        .isNotNull();
  }

  /** Returns the last exported server span of a request to the path. */
  private Optional<SpanData> lastServerSpanOf(String path) {
    return spans.reversed().stream()
        .filter(
            span -> span.getKind() == SpanKind.SERVER && path.equals(span.getAttributes().get(URL)))
        .findFirst();
  }

  /**
   * Keeps the spans.
   *
   * @param exportedSpans the spans to export
   * @return success
   */
  @Override
  public CompletableResultCode export(Collection<SpanData> exportedSpans) {
    spans.addAll(exportedSpans);
    return CompletableResultCode.ofSuccess();
  }

  /**
   * Does nothing: the spans are kept as they arrive.
   *
   * @return success
   */
  @Override
  public CompletableResultCode flush() {
    return CompletableResultCode.ofSuccess();
  }

  /**
   * Does nothing: the spans stay readable after the service stops.
   *
   * @return success
   */
  @Override
  public CompletableResultCode shutdown() {
    return CompletableResultCode.ofSuccess();
  }

  /**
   * What a request recorded on its timer and span.
   *
   * @param path the path the request was sent to
   * @param uri the timer's {@code uri} tag: the route's pattern, or the path when not given
   * @param exception the simple name of the recorded failure's class, or {@code none}
   * @param code the error code on the timer, or {@code none}, which the span leaves out
   * @param failed whether the span has the {@code ERROR} status
   */
  @StratumInternal
  @Builder(toBuilder = true)
  public record Recorded(String path, String uri, String exception, String code, boolean failed) {

    /** The tag value for no failure and no code. */
    public static final String NONE = "none";

    /** The code of the orders applications' duplicate order. */
    public static final String ORDER_CODE = OrderErrorCode.DUPLICATE_ORDER.getCode();

    /**
     * Makes the expectation; a route without path variables has its path as its pattern.
     *
     * @param path the path the request was sent to
     * @param uri the route's pattern; the path when not given
     * @param exception the simple name of the recorded failure's class, or {@code none}
     * @param code the error code on the timer, or {@code none}
     * @param failed whether the span has the {@code ERROR} status
     */
    public Recorded {
      uri = Objects.requireNonNullElse(uri, path);
    }

    /**
     * Makes the expectation for a request that ran out of time, which both stacks answer with a
     * 503.
     *
     * @param path the path the request was sent to
     * @return the expectation
     */
    public static Recorded timedOut(String path) {
      return builder()
          .path(path)
          .exception("RequestTimedOutException")
          .code(NONE)
          .failed(true)
          .build();
    }

    /** Builds the expectation; Lombok writes its methods, the label keeps it internal. */
    @StratumInternal
    public static class RecordedBuilder {}
  }
}
