package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = ReactiveOrdersApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.tracing.sampling.probability=1.0",
      // Stratum's startup check needs an address before @DynamicPropertySource runs; the
      // collector stub's address then replaces this one.
      "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
      "management.otlp.metrics.export.enabled=false",
      "orders.downstream-url=http://localhost:1"
    })
// Closes the application after this class, so its tracing never reaches the next one.
@DirtiesContext
@DisplayName("A failed request's span reaches the Collector without the exception's data")
class ExceptionFreeSpansIntegrationTest {

  /** Spring's timer of every request the service answers. */
  private static final String SERVER_REQUESTS_TIMER = "http.server.requests";

  /** The exception's details that the exported spans must not hold, as they appear in OTLP. */
  private static final List<String> EXCEPTION_DATA =
      List.of(ReactiveOrdersApplication.FAILURE_MESSAGE, "IllegalStateException", "exception");

  /** How long the test waits for the spans to reach the Collector. */
  private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(10);

  /** Stands in for the Collector and keeps every batch of spans it receives. */
  @AutoClose private static final CollectorStub COLLECTOR = new CollectorStub();

  /**
   * Sends the spans to the collector stub.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void sendSpansToCollectorStub(DynamicPropertyRegistry registry) {
    COLLECTOR.receiveSpans(registry);
  }

  /** The port the service listens on. */
  @LocalServerPort private int port;

  /** Sends the spans that ended at once, instead of after the export delay. */
  @Autowired private SdkTracerProvider tracerProvider;

  /** Holds the service's timers. */
  @Autowired private MeterRegistry meterRegistry;

  @Test
  @DisplayName(
      "has no exception type, message or stack trace, and its timer keeps the exception tag")
  void sendsSpanWithoutExceptionData() throws IOException, InterruptedException {
    assertThat(callFailingHandler().statusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());

    tracerProvider.forceFlush();
    var exportedSpans = awaitExportedSpans();
    assertThat(exportedSpans).contains(ReactiveOrdersApplication.FAILING_ORDER_PATH);
    assertThat(exportedSpans).doesNotContain(EXCEPTION_DATA);
    assertThat(
            meterRegistry
                .get(SERVER_REQUESTS_TIMER)
                .tag("uri", ReactiveOrdersApplication.FAILING_ORDER_PATH)
                .tag("exception", IllegalStateException.class.getSimpleName())
                .timer()
                .count())
        .isOne();
  }

  /** Calls the handler that always fails. */
  private HttpResponse<String> callFailingHandler() throws IOException, InterruptedException {
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(
                  URI.create(
                      "http://localhost:" + port + ReactiveOrdersApplication.FAILING_ORDER_PATH))
              .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }

  /**
   * Waits until the failed request's span reaches the Collector, and returns every batch as text.
   */
  private String awaitExportedSpans() {
    return await()
        .atMost(EXPORT_TIMEOUT)
        .until(
            () -> String.join("\n", COLLECTOR.bodiesOf(CollectorStub.TRACES_PATH)),
            bodies -> bodies.contains(ReactiveOrdersApplication.FAILING_ORDER_PATH));
  }
}
