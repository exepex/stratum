package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.web.testsupport.WebTestData;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;

@DisplayName("RequestLogWriter writes one line per request, never with a credential")
class RequestLogWriterTest {

  /** How long the logged request took. */
  private static final Duration TOOK = Duration.ofMillis(12);

  /** The status of a request that was answered. */
  private static final int OK = 200;

  /** A tenant header a service logs. */
  private static final String TENANT_HEADER = "X-Tenant-Id";

  /** A token payload, as a caller's identity provider writes it. */
  private static final String PAYLOAD = "{\"sub\":\"order-service\",\"scope\":\"orders.read\"}";

  /** The request log lines the test's writer wrote. */
  private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

  /** Returns the key-value pairs of the only line written. */
  private Map<String, Object> onlyLineFields() {
    assertThat(lines.list).hasSize(1);
    return lines.list.getFirst().getKeyValuePairs().stream()
        .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
  }

  /** Returns the request log settings a service gets when it sets nothing. */
  private WebProperties.RequestLogSettings defaultRequestLog() {
    return WebTestData.defaults().build().requestLog();
  }

  /** Returns a writer with the request log settings. */
  private RequestLogWriter writer(WebProperties.RequestLogSettings requestLog) {
    var settings = WebTestData.defaults().requestLog(requestLog).build();
    return new RequestLogWriter(settings, RequestFieldsReader.from(settings));
  }

  /** Returns a signed token whose payload part is the given JSON. */
  private String bearerToken(String payload) {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    return "Bearer eyJhbGciOiJSUzI1NiJ9."
        + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + ".c2lnbmF0dXJl";
  }

  /** Captures the request log lines and puts the request fields in the logging context. */
  @BeforeEach
  void captureLines() {
    lines.start();
    ((Logger) LoggerFactory.getLogger(WebConstants.REQUEST_LOG_LOGGER)).addAppender(lines);
    MDC.put(LoggingContextConstants.HTTP_METHOD, "GET");
    MDC.put(LoggingContextConstants.HTTP_ROUTE, "/orders/{orderId}");
  }

  /** Stops capturing and leaves no field behind. */
  @AfterEach
  void stopCapturing() {
    ((Logger) LoggerFactory.getLogger(WebConstants.REQUEST_LOG_LOGGER)).detachAppender(lines);
    MDC.clear();
  }

  @Nested
  @DisplayName("with the default settings")
  class DefaultSettings {

    /** The writer a service gets when it sets nothing. */
    private final RequestLogWriter writer = writer(defaultRequestLog());

    @Test
    @DisplayName("writes the method, route, status and duration, and no header")
    void writesStatusAndDuration() {
      var headers = new HttpHeaders();
      headers.set(TENANT_HEADER, "acme");

      writer.write(OK, TOOK, headers, new HttpHeaders());

      assertThat(lines.list.getFirst().getFormattedMessage())
          .isEqualTo("GET /orders/{orderId} answered 200 in 12 ms");
      assertThat(onlyLineFields())
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(WebConstants.HTTP_STATUS, OK, WebConstants.DURATION_MS, TOOK.toMillis()));
    }

    @Test
    @DisplayName("skips the actuator's paths and logs the others")
    void skipsActuator() {
      assertThat(writer.isLogged("/actuator/health/liveness")).isFalse();
      assertThat(writer.isLogged("/orders/42")).isTrue();
    }

    @Test
    @DisplayName("leaves the token payload out")
    void noTokenPayload() {
      var headers = new HttpHeaders();
      headers.set(HttpHeaders.AUTHORIZATION, bearerToken(PAYLOAD));

      writer.write(OK, TOOK, headers, new HttpHeaders());

      assertThat(onlyLineFields()).doesNotContainKey(WebConstants.TOKEN_PAYLOAD);
    }
  }

  @Nested
  @DisplayName("with the service's own settings")
  class OwnSettings {

    @Test
    @DisplayName("writes the named headers under their field names, safe for the logs")
    void writesNamedHeaders() {
      var requestHeaders = new HttpHeaders();
      requestHeaders.set(TENANT_HEADER.toLowerCase(Locale.ROOT), "acme\ncorp");
      var responseHeaders = new HttpHeaders();
      responseHeaders.addAll("X-Cache", List.of("HIT", "STALE"));

      writer(
              defaultRequestLog().toBuilder()
                  .requestHeaders(Map.of(TENANT_HEADER, "tenantId"))
                  .responseHeaders(Map.of("X-Cache", "cacheResult"))
                  .build())
          .write(OK, TOOK, requestHeaders, responseHeaders);

      assertThat(onlyLineFields())
          .containsEntry("tenantId", "acme corp")
          .containsEntry("cacheResult", "HIT, STALE");
    }

    @Test
    @DisplayName("never writes a credential header or a denied one, even when named")
    void neverWritesDeniedHeaders() {
      var headers = new HttpHeaders();
      headers.set(HttpHeaders.AUTHORIZATION, "Bearer abc");
      headers.set(HttpHeaders.COOKIE, "SESSION=abc");
      headers.set("x-api-key", "abc");
      headers.set("X-Internal-Key", "abc");

      writer(
              defaultRequestLog().toBuilder()
                  .requestHeaders(
                      Map.of(
                          "authorization",
                          "auth",
                          HttpHeaders.COOKIE,
                          "cookie",
                          "X-Api-Key",
                          "apiKey",
                          "X-Internal-Key",
                          "internalKey"))
                  .deniedHeaders(List.of("x-internal-key"))
                  .build())
          .write(OK, TOOK, headers, new HttpHeaders());

      assertThat(onlyLineFields())
          .containsOnlyKeys(WebConstants.HTTP_STATUS, WebConstants.DURATION_MS);
    }

    @Test
    @DisplayName("with the token payload on, writes only a signed bearer token's payload")
    void writesTokenPayload() {
      var tokenPayloadOn = writer(defaultRequestLog().toBuilder().tokenPayload(true).build());
      var signed = new HttpHeaders();
      signed.set(HttpHeaders.AUTHORIZATION, bearerToken(PAYLOAD));
      var encrypted = new HttpHeaders();
      encrypted.set(HttpHeaders.AUTHORIZATION, "Bearer a.b.c.d.e");

      tokenPayloadOn.write(OK, TOOK, signed, new HttpHeaders());
      var signedLine = onlyLineFields();
      lines.list.clear();
      tokenPayloadOn.write(OK, TOOK, encrypted, new HttpHeaders());

      assertThat(signedLine).containsEntry(WebConstants.TOKEN_PAYLOAD, PAYLOAD);
      assertThat(onlyLineFields()).doesNotContainKey(WebConstants.TOKEN_PAYLOAD);
    }

    @Test
    @DisplayName("writes nothing when the line is turned off")
    void turnedOff() {
      var off = writer(defaultRequestLog().toBuilder().enabled(false).build());

      assertThat(off.isLogged("/orders/42")).isFalse();
    }
  }
}
