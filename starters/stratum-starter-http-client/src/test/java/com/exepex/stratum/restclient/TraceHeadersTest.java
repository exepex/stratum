package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.testsupport.trace.TraceTestData;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("A client's trace headers step adds the correlation ID, or removes every trace header")
class TraceHeadersTest {

  /** The correlation ID in the logging context. */
  private static final String CORRELATION_ID = "checkout:777";

  /** A header that is not a trace header, which the step never touches. */
  private static final String TENANT = "X-Tenant";

  /** The baggage entry Spring Boot sends as a header of its own in the stripping tests. */
  private static final String REMOTE_FIELD = "region";

  /** Forgets the correlation ID a test put in the logging context. */
  @AfterEach
  void clearLoggingContext() {
    MDC.remove(LoggingContextConstants.CORRELATION_ID);
  }

  /** Returns the correlation ID headers after the step ran on the headers given. */
  private List<String> correlationIdsAfter(TraceHeaders step, HttpHeaders headers) {
    step.applyTo(headers);
    return headers.getOrEmpty(LoggingContextConstants.CORRELATION_ID_HEADER);
  }

  @Nested
  @DisplayName("with trace-headers: true")
  class Sending {

    /** The step of a client with the default setting. */
    private final TraceHeaders step = TraceHeaders.of(true, new MockEnvironment());

    @Test
    @DisplayName("the logging context's correlation ID is sent, and the tracing headers are kept")
    void sendsLoggingContextId() {
      MDC.put(LoggingContextConstants.CORRELATION_ID, CORRELATION_ID);
      var headers = new HttpHeaders();
      headers.set("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

      assertThat(correlationIdsAfter(step, headers)).containsExactly(CORRELATION_ID);
      assertThat(headers.containsHeader("traceparent")).isTrue();
    }

    @Test
    @DisplayName("a correlation ID the call already has wins: one value is sent")
    void teamValueWins() {
      MDC.put(LoggingContextConstants.CORRELATION_ID, CORRELATION_ID);
      var headers = new HttpHeaders();
      headers.set(LoggingContextConstants.CORRELATION_ID_HEADER, "team-id");

      assertThat(correlationIdsAfter(step, headers)).containsExactly("team-id");
    }

    @Test
    @DisplayName("with no correlation ID in the logging context, no header is sent")
    void noIdNoHeader() {
      assertThat(correlationIdsAfter(step, new HttpHeaders())).isEmpty();
    }
  }

  @Nested
  @DisplayName("with a correlation ID that is not safe for logs")
  class Unsafe {

    /**
     * IDs that are not 1 to 128 letters, digits, {@code -}, {@code _}, {@code .} or {@code :}:
     * empty, with a line break that could open a header, with a space, and 129 characters long.
     */
    private static final List<String> UNSAFE_IDS =
        List.of("", "checkout\r\nX-Injected: yes", "checkout 777", "a".repeat(129));

    /** The step of a client with the default setting. */
    private final TraceHeaders step = TraceHeaders.of(true, new MockEnvironment());

    @ParameterizedTest
    @FieldSource("UNSAFE_IDS")
    @DisplayName("the ID is not sent")
    void unsafeIdNotSent(String unsafeId) {
      MDC.put(LoggingContextConstants.CORRELATION_ID, unsafeId);

      assertThat(correlationIdsAfter(step, new HttpHeaders())).isEmpty();
    }
  }

  @Nested
  @DisplayName("with trace-headers: false")
  class Stripping {

    /** The step of a client set to false, with one remote field in Spring Boot's settings. */
    private final TraceHeaders step =
        TraceHeaders.of(
            false,
            new MockEnvironment()
                .withProperty(HttpClientConstants.BAGGAGE_REMOTE_FIELDS, REMOTE_FIELD));

    @Test
    @DisplayName("every trace header and remote field is removed, whatever its case; others stay")
    void removesEveryTraceHeader() {
      var headers = new HttpHeaders();
      HttpClientConstants.TRACE_HEADERS.forEach(
          name -> headers.set(name.toUpperCase(Locale.ROOT), "set by anyone"));
      headers.set(REMOTE_FIELD.toUpperCase(Locale.ROOT), "eu");
      headers.set(TENANT, "acme");

      step.applyTo(headers);

      assertThat(headers.headerNames()).containsExactly(TENANT);
    }

    @Test
    @DisplayName("removes the W3C, baggage, B3 and correlation headers, each named on its own")
    void removesEachNamedHeader() {
      var headers = new HttpHeaders();
      TraceTestData.STRIPPED_HEADERS.forEach(name -> headers.set(name, "set by anyone"));

      step.applyTo(headers);

      assertThat(headers.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("the logging context's correlation ID is not sent")
    void noIdFromLoggingContext() {
      MDC.put(LoggingContextConstants.CORRELATION_ID, CORRELATION_ID);

      assertThat(correlationIdsAfter(step, new HttpHeaders())).isEmpty();
    }
  }
}
