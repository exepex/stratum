package com.exepex.stratum.restclient.it.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.testsupport.EchoDownstream;
import com.exepex.stratum.restclient.testsupport.headers.HeadersTestData;
import com.exepex.stratum.restclient.testsupport.trace.ThirdPartyClient;
import com.exepex.stratum.restclient.testsupport.trace.TraceApplication;
import com.exepex.stratum.restclient.testsupport.trace.TraceTestData;
import com.exepex.stratum.restclient.testsupport.trace.TracedClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * With the observability starter's tracing, each call runs in a trace whose baggage holds a
 * correlation ID and a {@code region} entry, which Spring Boot sends as a header of its own. The
 * traced client copies {@code X-Correlation-Id} from the request the service answers; the third
 * party's client is set to {@code trace-headers: false} and its interceptor sets trace headers.
 */
@SpringBootTest(
    classes = TraceApplication.class,
    properties = {
      "spring.main.web-application-type=none",
      "management.tracing.sampling.probability=1.0",
      // Stratum's startup check needs an address before @DynamicPropertySource runs; the spans
      // server's address then replaces this one.
      "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
      "management.otlp.metrics.export.enabled=false",
      HttpClientConstants.BAGGAGE_REMOTE_FIELDS + "=" + TraceHeadersIntegrationTest.REGION,
      TraceTestData.TRACED_SETTINGS
          + ".copy-headers="
          + LoggingContextConstants.CORRELATION_ID_HEADER,
      TraceTestData.THIRD_PARTY_SETTINGS + ".trace-headers=false"
    })
@DisplayName("A client sends the trace and correlation headers, or none of them with false")
class TraceHeadersIntegrationTest {

  /** The baggage entry Spring Boot sends as a header of its own. */
  static final String REGION = "region";

  /** The value of the region entry. */
  private static final String REGION_VALUE = "eu";

  /** The server both clients call. */
  @AutoClose private static final EchoDownstream DOWNSTREAM = EchoDownstream.start();

  /** The server that takes the spans, so the application never retries a failed send. */
  @AutoClose private static final EchoDownstream SPANS = EchoDownstream.start();

  @Autowired private TracedClient traced;

  @Autowired private ThirdPartyClient thirdParty;

  @Autowired private ObservationRegistry observations;

  @Autowired private Tracer tracer;

  @Autowired private MeterRegistry meters;

  /**
   * Points the clients and the span export at the servers.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    TraceTestData.pointClientsAt(registry, DOWNSTREAM::url);
    registry.add(
        "management.opentelemetry.tracing.export.otlp.endpoint", () -> SPANS.url() + "/v1/traces");
  }

  /** Forgets the request a test bound, if any. */
  @AfterEach
  void unbindRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  /** Runs a call in a trace whose baggage holds the correlation ID and the region. */
  private void inTrace(Runnable call) {
    Observation.createNotStarted("checkout", observations).observe(() -> withBaggage(call));
  }

  /** Runs a call with the correlation ID and the region in the baggage. */
  private void withBaggage(Runnable call) {
    try (var _ =
            tracer.createBaggageInScope(
                LoggingContextConstants.CORRELATION_ID, TraceTestData.CORRELATION_ID);
        var _ = tracer.createBaggageInScope(REGION, REGION_VALUE)) {
      call.run();
    }
  }

  /** Returns the lines of one header in the last call the server got. */
  private List<String> sent(String header) {
    return DOWNSTREAM.last().values(header);
  }

  @Nested
  @DisplayName("with trace-headers: true, the default")
  class Default {

    @Test
    @DisplayName("traceparent, baggage, the remote field and X-Correlation-Id arrive")
    void headersSent() {
      inTrace(traced::echo);

      assertThat(sent("traceparent")).hasSize(1);
      assertThat(sent(LoggingContextConstants.BAGGAGE_HEADER))
          .singleElement()
          .asString()
          .contains(LoggingContextConstants.CORRELATION_ID + "=" + TraceTestData.CORRELATION_ID);
      assertThat(sent(REGION)).containsExactly(REGION_VALUE);
      assertThat(sent(LoggingContextConstants.CORRELATION_ID_HEADER))
          .containsExactly(TraceTestData.CORRELATION_ID);
    }

    @Test
    @DisplayName("a team's own X-Correlation-Id wins: one value is sent")
    void teamValueWins() {
      inTrace(() -> traced.echoWith(TraceTestData.TEAM_CORRELATION_ID));

      assertThat(sent(LoggingContextConstants.CORRELATION_ID_HEADER))
          .containsExactly(TraceTestData.TEAM_CORRELATION_ID);
    }
  }

  @Nested
  @DisplayName("with trace-headers: false")
  class Off {

    @Test
    @DisplayName(
        "none of the trace headers arrive, an interceptor's included; the call is observed")
    void noneSent() {
      var observedBefore = observedCalls();

      inTrace(thirdParty::echo);

      assertThat(Stream.concat(HttpClientConstants.TRACE_HEADERS.stream(), Stream.of(REGION)))
          .allSatisfy(header -> assertThat(sent(header)).as(header).isEmpty());
      assertThat(observedCalls()).isEqualTo(observedBefore + 1);
    }

    /** Returns how many calls Spring's client observation has recorded. */
    private long observedCalls() {
      return meters.find("http.client.requests").timers().stream().mapToLong(Timer::count).sum();
    }
  }

  @Nested
  @DisplayName("in the order of the steps")
  class StepOrder {

    @Test
    @DisplayName("a correlation ID copied from the request the service answers wins")
    void copiedValueWins() {
      HeadersTestData.answering(
          LoggingContextConstants.CORRELATION_ID_HEADER, TraceTestData.TEAM_CORRELATION_ID);

      inTrace(traced::echo);

      assertThat(sent(LoggingContextConstants.CORRELATION_ID_HEADER))
          .containsExactly(TraceTestData.TEAM_CORRELATION_ID);
    }
  }
}
