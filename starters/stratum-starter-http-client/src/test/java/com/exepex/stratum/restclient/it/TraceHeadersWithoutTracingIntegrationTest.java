package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.restclient.testsupport.EchoDownstream;
import com.exepex.stratum.restclient.testsupport.trace.ThirdPartyClient;
import com.exepex.stratum.restclient.testsupport.trace.TraceApplication;
import com.exepex.stratum.restclient.testsupport.trace.TraceSettingInterceptor;
import com.exepex.stratum.restclient.testsupport.trace.TraceTestData;
import com.exepex.stratum.restclient.testsupport.trace.TracedClient;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * No tracing library is on this execution's classpath, so the logging context holds a correlation
 * ID only when a test puts one there. The traced client has neither copied headers nor an
 * interceptor.
 */
@SpringBootTest(
    classes = TraceApplication.class,
    properties = TraceTestData.THIRD_PARTY_SETTINGS + ".trace-headers=false")
@DisplayName("Without tracing, a client sends the logging context's correlation ID when it has one")
class TraceHeadersWithoutTracingIntegrationTest {

  /** The server both clients call. */
  @AutoClose private static final EchoDownstream DOWNSTREAM = EchoDownstream.start();

  @Autowired private TracedClient traced;

  @Autowired private ThirdPartyClient thirdParty;

  /**
   * Points the clients at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    TraceTestData.pointClientsAt(registry, DOWNSTREAM::url);
  }

  /** Forgets the correlation ID a test put in the logging context. */
  @AfterEach
  void clearLoggingContext() {
    MDC.remove(LoggingContextConstants.CORRELATION_ID);
  }

  /** Returns the correlation ID headers of the last call the server got. */
  private List<String> correlationIdsSent() {
    return DOWNSTREAM.last().values(LoggingContextConstants.CORRELATION_ID_HEADER);
  }

  @Test
  @DisplayName("with no correlation ID in the logging context, no header is sent")
  void noIdNoHeader() {
    traced.echo();

    assertThat(correlationIdsSent()).isEmpty();
  }

  @Test
  @DisplayName("a client with no copied header and no interceptor sends the logging context's ID")
  void plainClientSendsId() {
    MDC.put(LoggingContextConstants.CORRELATION_ID, TraceTestData.CORRELATION_ID);

    traced.echo();

    assertThat(correlationIdsSent()).containsExactly(TraceTestData.CORRELATION_ID);
  }

  @Test
  @DisplayName("trace-headers: false removes the headers the client's own interceptor set")
  void falseRemovesInterceptorHeaders() {
    MDC.put(LoggingContextConstants.CORRELATION_ID, TraceTestData.CORRELATION_ID);

    thirdParty.echo();

    assertThat(correlationIdsSent()).isEmpty();
    assertThat(DOWNSTREAM.last().values(TraceSettingInterceptor.TRACEPARENT)).isEmpty();
  }
}
