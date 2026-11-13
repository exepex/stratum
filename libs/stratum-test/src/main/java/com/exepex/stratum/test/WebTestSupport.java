package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.experimental.UtilityClass;

/**
 * The calls and checks that the web modules' tests share: requests to a service under test, checks
 * of its error answers and request fields, and the test values those requests carry.
 */
@StratumInternal
@UtilityClass
public class WebTestSupport {

  /** The media type of every error body the web starters send. */
  private static final String PROBLEM_JSON = "application/problem+json";

  /** The media type of a text body. */
  private static final String TEXT_PLAIN = "text/plain";

  /** The address that requests sent from this machine come from. */
  private static final String LOOPBACK_ADDRESS = ipAddress(127, 0, 0, 1);

  /** Calls the services under test; one client serves every test. */
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  /** The number of bytes in an IPv4 address. */
  private static final int IPV4_BYTES = 4;

  /**
   * The timer Spring Boot records for each request a service answers; {@link
   * ServerRequestTelemetry} reads it too.
   */
  static final String SERVER_REQUESTS_TIMER = "http.server.requests";

  /** How long a test waits for a service to time a request. */
  private static final Duration TIMER_WAIT = Duration.ofSeconds(5);

  /** What a time-left test endpoint answers when the request has no limit. */
  public static final String NO_TIME_LIMIT = "none";

  /**
   * Checks an error answer of a web starter: its status, the Problem Details media type, the {@code
   * status} and {@code code} members of the body, and a 32-hex {@code traceId}.
   *
   * @param response the answer
   * @param status the HTTP status it must have
   * @param code the error code its body must have, such as {@code SX-WEB-1003}
   */
  public static void checkProblemDetails(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type")).hasValue(PROBLEM_JSON);
    assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo(code);
    assertThat(JsonPath.<Integer>read(response.body(), "$.status")).isEqualTo(status);
    assertThat(JsonPath.<String>read(response.body(), "$." + LoggingContextConstants.TRACE_ID))
        .matches("\\p{XDigit}{32}");
  }

  /**
   * Sends a GET request that must answer 200, then waits until the service has timed it, which is
   * when its observation, and any span it made, has ended.
   *
   * @param port the service's port
   * @param path the path to call
   * @param meterRegistry the service's meter registry
   * @throws IOException if the request cannot be sent or the response read
   * @throws InterruptedException if the thread is interrupted while it waits
   */
  public static void getAndAwaitTimer(int port, String path, MeterRegistry meterRegistry)
      throws IOException, InterruptedException {
    var timedBefore = requestsTimed(meterRegistry);
    assertThat(getFromService(port, path).statusCode()).isEqualTo(HttpURLConnection.HTTP_OK);
    await().atMost(TIMER_WAIT).until(() -> requestsTimed(meterRegistry) > timedBefore);
  }

  /**
   * Returns how many requests a service has timed so far.
   *
   * @param meterRegistry the service's meter registry
   * @return the sum of the counts of its request timers
   */
  private static long requestsTimed(MeterRegistry meterRegistry) {
    return meterRegistry.find(SERVER_REQUESTS_TIMER).timers().stream()
        .mapToLong(Timer::count)
        .sum();
  }

  /**
   * Sends a GET request to a service running on this machine, as its integration tests do.
   *
   * @param port the service's port
   * @param pathAndQuery the path and query, such as {@code /orders/42?expand=lines}
   * @param headers header names and values, in pairs
   * @return the response, with its body as text
   * @throws IOException if the request cannot be sent or the response read
   * @throws InterruptedException if the thread is interrupted while it waits
   */
  public static HttpResponse<String> getFromService(
      int port, String pathAndQuery, String... headers) throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery)).GET();
    if (headers.length > 0) {
      request.headers(headers);
    }
    return HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  /**
   * Sends a POST request with a body of {@code byteCount} bytes to a service running on this
   * machine, as its integration tests do.
   *
   * @param port the service's port
   * @param path the path, such as {@code /orders}
   * @param contentType the body's media type, such as {@code text/plain}
   * @param byteCount how large the body is
   * @param declareLength whether the request has a {@code Content-Length} header; without one the
   *     body is sent in chunks, as a stream of unknown length is
   * @return the response, with its body as text
   * @throws IOException if the request cannot be sent or the response read
   * @throws InterruptedException if the thread is interrupted while it waits
   */
  private static HttpResponse<String> postToService(
      int port, String path, String contentType, int byteCount, boolean declareLength)
      throws IOException, InterruptedException {
    var body = "x".repeat(byteCount).getBytes(StandardCharsets.US_ASCII);
    var publisher =
        declareLength
            ? HttpRequest.BodyPublishers.ofByteArray(body)
            : HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body));
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", contentType)
            .POST(publisher)
            .build();
    return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  /**
   * Sends a text body of {@code byteCount} bytes and checks that the service read all of it: the
   * answer is {@code 200} with the number of bytes read as its body, as the body limit tests'
   * endpoints answer.
   *
   * @param port the service's port
   * @param path the path, such as {@code /uploads/raw}
   * @param byteCount how large the body is
   * @param declareLength whether the request has a {@code Content-Length} header
   * @throws IOException if the request cannot be sent or the response read
   * @throws InterruptedException if the thread is interrupted while it waits
   */
  public static void checkBodyAccepted(int port, String path, int byteCount, boolean declareLength)
      throws IOException, InterruptedException {
    var response = postToService(port, path, TEXT_PLAIN, byteCount, declareLength);

    assertThat(response.statusCode()).isEqualTo(HttpURLConnection.HTTP_OK);
    assertThat(response.body()).isEqualTo(String.valueOf(byteCount));
  }

  /**
   * Sends a body of {@code byteCount} bytes and checks that the service refused it as too large:
   * {@code 413} with the code, in Problem Details.
   *
   * @param port the service's port
   * @param path the path, such as {@code /orders}
   * @param contentType the body's media type, such as {@code text/plain}
   * @param byteCount how large the body is
   * @param declareLength whether the request has a {@code Content-Length} header
   * @param code the error code the body must have, {@code SX-WEB-1008}
   * @throws IOException if the request cannot be sent or the response read
   * @throws InterruptedException if the thread is interrupted while it waits
   */
  public static void checkBodyRefused(
      int port, String path, String contentType, int byteCount, boolean declareLength, String code)
      throws IOException, InterruptedException {
    var response = postToService(port, path, contentType, byteCount, declareLength);

    checkProblemDetails(response, HttpURLConnection.HTTP_ENTITY_TOO_LARGE, code);
  }

  /**
   * Returns an {@code Authorization} header value with a signed JWT whose payload is the given
   * JSON. The token's header and signature are fixed test values.
   *
   * @param payloadJson the token's payload, such as {@code {"sub":"checkout-service"}}
   * @return the header value, starting with {@code Bearer }
   */
  public static String bearerToken(String payloadJson) {
    return "Bearer eyJhbGciOiJSUzI1NiJ9."
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8))
        + ".c2lnbmF0dXJl";
  }

  /**
   * Checks the request fields that both web starters put in the logging context of a GET request
   * sent from this machine, as returned by a test endpoint that answers with its logging context:
   * the method, route, path, query, client IP and raw user agent, and none of the parsed agent or
   * location fields, which are off by default.
   *
   * @param response the endpoint's response, whose body is the logging context as JSON
   * @param route the route template, such as {@code /orders/{orderId}/fields}
   * @param pathAndQuery the path and query sent, such as {@code /orders/42/fields?expand=lines}
   * @param userAgent the {@code User-Agent} header sent
   */
  public static void checkRequestFields(
      HttpResponse<String> response, String route, String pathAndQuery, String userAgent) {
    var sent = URI.create(pathAndQuery);
    Map<String, String> fields = JsonPath.read(response.body(), "$");
    assertThat(fields)
        .containsEntry(LoggingContextConstants.HTTP_METHOD, "GET")
        .containsEntry(LoggingContextConstants.HTTP_ROUTE, route)
        .containsEntry(LoggingContextConstants.HTTP_PATH, sent.getPath())
        .containsEntry(LoggingContextConstants.HTTP_QUERY, sent.getQuery())
        .containsEntry(LoggingContextConstants.CLIENT_IP, LOOPBACK_ADDRESS)
        .containsEntry(LoggingContextConstants.USER_AGENT, userAgent)
        .doesNotContainKeys(
            LoggingContextConstants.DEVICE_CLASS, LoggingContextConstants.CLIENT_COUNTRY);
  }

  /**
   * Returns the text of an IP address from its numbers: four decimal numbers make an IPv4 address,
   * such as {@code ipAddress(198, 51, 100, 20)} for {@code 198.51.100.20}, and eight make an IPv6
   * address, such as {@code ipAddress(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1)} for {@code 2001:db8::1}.
   * Tests write addresses this way because PMD's {@code AvoidUsingHardCodedIP} rule, which keeps
   * server addresses out of code, rejects address literals.
   *
   * @param numbers the four bytes of an IPv4 address, or the eight groups of an IPv6 address
   * @return the address as text, such as {@code 198.51.100.20} or {@code 2001:db8:0:0:0:0:0:1}
   */
  public static String ipAddress(int... numbers) {
    return numbers.length == IPV4_BYTES
        ? IntStream.of(numbers).mapToObj(Integer::toString).collect(Collectors.joining("."))
        : IntStream.of(numbers).mapToObj(Integer::toHexString).collect(Collectors.joining(":"));
  }

  /**
   * Returns a request's time left as the time-left test endpoints of both web starters answer it.
   *
   * @param timeLeft the time left, from {@code RequestTimeLeft}
   * @return the milliseconds left, or {@value #NO_TIME_LIMIT}
   */
  public static String describeTimeLeft(Optional<Duration> timeLeft) {
    return timeLeft.map(Duration::toMillis).map(String::valueOf).orElse(NO_TIME_LIMIT);
  }

  /**
   * Checks that a time-left endpoint answered with a time above zero and at most the limit.
   *
   * @param answer the answer
   * @param limitMillis the request's limit, in milliseconds
   */
  public static void checkTimeLeftUpTo(HttpResponse<String> answer, long limitMillis) {
    assertThat(answer.statusCode()).isEqualTo(HttpURLConnection.HTTP_OK);
    assertThat(Long.parseLong(answer.body())).isPositive().isLessThanOrEqualTo(limitMillis);
  }

  /**
   * Checks that a time-left endpoint answered that the request has no limit.
   *
   * @param answer the answer
   */
  public static void checkNoTimeLimit(HttpResponse<String> answer) {
    assertThat(answer.statusCode()).isEqualTo(HttpURLConnection.HTTP_OK);
    assertThat(answer.body()).isEqualTo(NO_TIME_LIMIT);
  }
}
