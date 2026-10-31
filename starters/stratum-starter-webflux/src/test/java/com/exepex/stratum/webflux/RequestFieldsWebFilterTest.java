package com.exepex.stratum.webflux;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.RequestFieldValues;
import com.exepex.stratum.web.RequestFieldsReader;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@DisplayName("RequestFieldsWebFilter works in a service's own WebTestClient tests")
class RequestFieldsWebFilterTest {

  /** The address of the mock request's client. */
  private static final String CLIENT_ADDRESS = StarterTestSupport.ipAddress(198, 51, 100, 20);

  /** The port of the mock request's client. */
  private static final int CLIENT_PORT = 50_000;

  /** A filter with the default settings. */
  private final RequestFieldsWebFilter filter =
      new RequestFieldsWebFilter(
          RequestFieldsReader.from(WebProperties.resolve(new MockEnvironment())));

  @Test
  @DisplayName("a mock request, which has no server request, gets its client IP from the mock")
  void mockRequestGetsFields() {
    var fieldsSeenByChain = new AtomicReference<RequestFieldValues>();
    WebFilterChain chain =
        exchange ->
            Mono.deferContextual(
                context ->
                    Mono.<Void>fromRunnable(
                        () ->
                            fieldsSeenByChain.set(
                                context.get(WebConstants.REQUEST_FIELDS_CONTEXT_KEY))));
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/orders/42")
                .remoteAddress(new InetSocketAddress(CLIENT_ADDRESS, CLIENT_PORT)));

    filter.filter(exchange, chain).block();

    assertThat(fieldsSeenByChain.get().fields())
        .containsEntry(LoggingContextConstants.HTTP_PATH, "/orders/42")
        .containsEntry(LoggingContextConstants.CLIENT_IP, CLIENT_ADDRESS);
  }
}
