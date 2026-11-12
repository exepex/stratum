package com.exepex.stratum.test.it;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.test.BackwardCompatibleErrorChecks;
import com.exepex.stratum.test.WebTestSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("WebTestSupport and BackwardCompatibleErrorChecks call a running service")
class WebTestSupportIntegrationTest {

  /** The media type of a text body. */
  private static final String TEXT_PLAIN = "text/plain";

  /** The size of the bodies sent. */
  private static final int BODY_BYTES = 2048;

  /** The code the refusing endpoint answers with. */
  private static final String TOO_LARGE_CODE = "SX-TEST-1008";

  /** The error body the refusing endpoint answers with, as a web starter's is. */
  private static final String TOO_LARGE_BODY =
      "{\"status\":413,\"code\":\"" + TOO_LARGE_CODE + "\",\"traceId\":\"" + "a".repeat(32) + "\"}";

  /** The path that answers as a backward-compatible service answers an unknown path. */
  private static final String UNKNOWN_PATH = "/no-such-path";

  /** The trace ID the answers carry. */
  private static final String TRACE_ID = "b".repeat(32);

  /** Spring Boot's body for an unknown path. */
  private static final String BOOT_BODY =
      "{\"status\":404,\"error\":\"Not Found\",\"message\":\"No static resource\","
          + "\"path\":\""
          + UNKNOWN_PATH
          + "\"}";

  /** Stratum's body for an unknown path. */
  private static final String STANDARD_BODY =
      "{\"status\":404,\"code\":\"SX-TEST-1001\",\"traceId\":\"" + TRACE_ID + "\"}";

  /** Stratum's page for an unknown path. */
  private static final String REFERENCE_PAGE =
      "<title>Not Found</title><p>Reference: <code>" + TRACE_ID + "</code></p>";

  /** The path whose answers the server times, as Spring Boot times a request. */
  private static final String TIMED_PATH = "/timed";

  /** The timer Spring Boot records for each request a service answers. */
  private static final String SERVER_REQUESTS_TIMER = "http.server.requests";

  /** How long after its answer the server times a request. */
  private static final Duration TIMER_DELAY = Duration.ofMillis(200);

  /** The server's timers. */
  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  private HttpServer server;

  /** Starts a server that echoes a header, reads a body, or refuses a body as too large. */
  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/echo",
        exchange ->
            answer(
                exchange,
                HttpURLConnection.HTTP_OK,
                TEXT_PLAIN,
                exchange.getRequestHeaders().getFirst("X-Order")));
    server.createContext(
        "/accepted",
        exchange ->
            answer(
                exchange,
                HttpURLConnection.HTTP_OK,
                TEXT_PLAIN,
                String.valueOf(exchange.getRequestBody().readAllBytes().length)));
    server.createContext(
        "/refused",
        exchange ->
            answer(
                exchange,
                HttpURLConnection.HTTP_ENTITY_TOO_LARGE,
                "application/problem+json",
                TOO_LARGE_BODY));
    server.createContext(TIMED_PATH, this::answerAndTime);
    server.createContext(UNKNOWN_PATH, this::answerUnknownPath);
    server.createContext(
        "/coded",
        exchange ->
            answer(
                exchange,
                HttpURLConnection.HTTP_NOT_FOUND,
                "application/json",
                BOOT_BODY
                    .replace(UNKNOWN_PATH, "/coded")
                    .replace("}", ",\"code\":\"SX-TEST-1001\"}")));
    server.start();
  }

  /** Stops the server. */
  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Nested
  @DisplayName("getFromService")
  class GetFromService {

    @Test
    @DisplayName("sends a GET request with the headers and returns the body")
    void sendsRequestWithHeaders() throws IOException, InterruptedException {
      var response = WebTestSupport.getFromService(port(), "/echo", "X-Order", "42");

      assertThat(response.body()).isEqualTo("42");
    }
  }

  @Nested
  @DisplayName("getAndAwaitTimer")
  class GetAndAwaitTimer {

    @Test
    @DisplayName("returns once the server has timed the request, even after it answered")
    void waitsForTimer() throws IOException, InterruptedException {
      WebTestSupport.getAndAwaitTimer(port(), TIMED_PATH, meterRegistry);

      assertThat(meterRegistry.get(SERVER_REQUESTS_TIMER).timer().count()).isOne();
    }

    @Test
    @DisplayName("rejects an answer that is not 200")
    void rejectsFailedRequest() {
      var serverPort = port();

      assertThatExceptionOfType(AssertionError.class)
          .isThrownBy(
              () -> WebTestSupport.getAndAwaitTimer(serverPort, UNKNOWN_PATH, meterRegistry));
    }
  }

  @Nested
  @DisplayName("checkBodyAccepted")
  class CheckBodyAccepted {

    /**
     * Whether the request declares its body's length: with {@code Content-Length}, then chunked.
     */
    private static final List<Boolean> DECLARED_AND_STREAMED = List.of(true, false);

    @ParameterizedTest(name = "with Content-Length: {0}")
    @FieldSource("DECLARED_AND_STREAMED")
    @DisplayName("sends the whole body, declared or streamed")
    void sendsWholeBody(boolean declareLength) throws IOException, InterruptedException {
      WebTestSupport.checkBodyAccepted(port(), "/accepted", BODY_BYTES, declareLength);
    }
  }

  @Nested
  @DisplayName("checkBodyRefused")
  class CheckBodyRefused {

    @Test
    @DisplayName("accepts a 413 with the code")
    void readsRefusal() throws IOException, InterruptedException {
      WebTestSupport.checkBodyRefused(
          port(), "/refused", TEXT_PLAIN, BODY_BYTES, true, TOO_LARGE_CODE);
    }
  }

  @Nested
  @DisplayName("BackwardCompatibleErrorChecks")
  class BackwardCompatibleChecks {

    @Test
    @DisplayName("accepts Spring Boot's body, Stratum's body on request and the reference page")
    void acceptsCompatibleAnswers() throws IOException, InterruptedException {
      BackwardCompatibleErrorChecks.checkBootBody(port(), UNKNOWN_PATH);
      BackwardCompatibleErrorChecks.checkOptedInBody(port(), UNKNOWN_PATH, "SX-TEST-1001");
      BackwardCompatibleErrorChecks.checkBrowserPage(
          port(), UNKNOWN_PATH, HttpURLConnection.HTTP_NOT_FOUND, "Not Found");
      BackwardCompatibleErrorChecks.checkCodedFailureBody(
          port(), "/refused", HttpURLConnection.HTTP_ENTITY_TOO_LARGE, TOO_LARGE_CODE);
    }

    @Test
    @DisplayName("rejects a body that holds a Stratum code where Spring Boot's is expected")
    void rejectsStratumCode() {
      var serverPort = port();

      assertThatExceptionOfType(AssertionError.class)
          .isThrownBy(() -> BackwardCompatibleErrorChecks.checkBootBody(serverPort, "/coded"));
    }
  }

  /**
   * Answers, then times the request a little later, as Spring Boot may once the response has gone
   * out, so a caller that does not wait finds no timer.
   */
  private void answerAndTime(HttpExchange exchange) throws IOException {
    answer(exchange, HttpURLConnection.HTTP_OK, TEXT_PLAIN, "UP");
    CompletableFuture.delayedExecutor(TIMER_DELAY.toMillis(), TimeUnit.MILLISECONDS)
        .execute(() -> meterRegistry.timer(SERVER_REQUESTS_TIMER).record(TIMER_DELAY));
  }

  /** Answers an unknown path with the body or page the request's Accept header asks for. */
  private void answerUnknownPath(HttpExchange exchange) throws IOException {
    var accept = String.valueOf(exchange.getRequestHeaders().getFirst("Accept"));
    if (accept.contains("problem+json")) {
      answer(exchange, HttpURLConnection.HTTP_NOT_FOUND, "application/problem+json", STANDARD_BODY);
    } else if (accept.contains("text/html")) {
      answer(exchange, HttpURLConnection.HTTP_NOT_FOUND, "text/html", REFERENCE_PAGE);
    } else {
      answer(exchange, HttpURLConnection.HTTP_NOT_FOUND, "application/json", BOOT_BODY);
    }
  }

  /** The server's port. */
  private int port() {
    return server.getAddress().getPort();
  }

  /** Reads what is left of the request body, then answers with the status, type and body. */
  private void answer(HttpExchange exchange, int status, String contentType, String body)
      throws IOException {
    exchange.getRequestBody().readAllBytes();
    var bytes = body.getBytes(UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
