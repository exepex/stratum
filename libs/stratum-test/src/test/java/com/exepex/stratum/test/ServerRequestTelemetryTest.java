package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.test.ServerRequestTelemetry.Recorded;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

@DisplayName("ServerRequestTelemetry checks a request's timer and server span")
class ServerRequestTelemetryTest {

  /** A request that failed with the orders' duplicate order. */
  private static final Recorded DUPLICATE = ServerRequestTelemetry.DUPLICATE_ORDER;

  /** The service's beans: its tracer provider and meters. */
  private final StaticListableBeanFactory beans = new StaticListableBeanFactory();

  /** The service's meters. */
  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  /** The checker, as the service's span exporter. */
  private final ServerRequestTelemetry telemetry =
      new ServerRequestTelemetry(
          beans.getBeanProvider(SdkTracerProvider.class),
          beans.getBeanProvider(MeterRegistry.class));

  /** The service's tracer provider, which exports to the checker. */
  private final SdkTracerProvider tracerProvider =
      SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(telemetry)).build();

  /** Registers the service's beans. */
  @BeforeEach
  void registerBeans() {
    beans.addBean("tracerProvider", tracerProvider);
    beans.addBean("meterRegistry", meterRegistry);
  }

  /** Stops the tracer provider. */
  @AfterEach
  void stopTracing() {
    tracerProvider.close();
  }

  /** Records a request's timer, and its server span with the given status and code. */
  private void recordRequest(StatusCode status, String spanCode) {
    meterRegistry
        .timer(
            "http.server.requests",
            "uri",
            DUPLICATE.uri(),
            "error",
            DUPLICATE.exception(),
            "exception",
            DUPLICATE.exception(),
            "error.code",
            DUPLICATE.code())
        .record(Duration.ofMillis(1));
    var span =
        tracerProvider
            .get("test")
            .spanBuilder("http get")
            .setSpanKind(SpanKind.SERVER)
            .setAttribute("http.url", DUPLICATE.path())
            .setAttribute("error.code", spanCode)
            .startSpan();
    span.setStatus(status);
    span.end();
  }

  @Test
  @DisplayName("passes when the timer's tags and the span's status and code are as expected")
  void passesForExpectedRequest() {
    recordRequest(StatusCode.ERROR, DUPLICATE.code());

    telemetry.check(DUPLICATE);

    assertThat(telemetry.flush().isSuccess()).isTrue();
    assertThat(telemetry.shutdown().isSuccess()).isTrue();
  }

  @Test
  @DisplayName("fails when the span is not marked as failed")
  void failsForUnmarkedSpan() {
    recordRequest(StatusCode.UNSET, DUPLICATE.code());

    assertThatThrownBy(() -> telemetry.check(DUPLICATE)).isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("fails when the timer has no such tags")
  void failsForMissingTimer() {
    recordRequest(StatusCode.ERROR, DUPLICATE.code());

    var otherRoute =
        new Recorded(
            DUPLICATE.path(),
            "/other",
            DUPLICATE.exception(),
            DUPLICATE.code(),
            DUPLICATE.failed());

    assertThatThrownBy(() -> telemetry.check(otherRoute)).isInstanceOf(AssertionError.class);
  }
}
