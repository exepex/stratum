package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.HttpClientConstants;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.IntervalFunction;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

@DisplayName("Stratum sends a call again only when that is safe and there is time left")
class RetryRulesTest {

  /** A wait that every test of the time left uses. */
  private static final Duration WAIT = Duration.ofMillis(200);

  /** The moment a {@code Retry-After} date is read against. */
  private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

  /** Just enough time left after {@link #WAIT} for one more attempt of at least 100ms. */
  private static final Duration JUST_ENOUGH = WAIT.plus(HttpClientConstants.MIN_ATTEMPT_TIME);

  /** The time left before a call's total-timeout when a test does not care. */
  private static final Duration PLENTY = HttpClientConstants.DEFAULT_TOTAL_TIMEOUT;

  /** An attempt far past the back-off's growth, whose wait is the longest. */
  private static final int LATE_ATTEMPT = 10;

  /** A back-off that always waits {@link #WAIT}, so that a wait can be told from it. */
  private static final IntervalFunction FIXED_BACK_OFF = IntervalFunction.of(WAIT);

  @Nested
  @DisplayName("after an error answer")
  class ErrorAnswers {

    /** The answers after which an idempotent call is sent again. */
    private static final List<HttpStatus> RETRIED =
        List.of(
            HttpStatus.BAD_GATEWAY,
            HttpStatus.SERVICE_UNAVAILABLE,
            HttpStatus.GATEWAY_TIMEOUT,
            HttpStatus.TOO_MANY_REQUESTS);

    /** Error answers after which no call is sent again. */
    private static final List<HttpStatus> NOT_RETRIED =
        List.of(
            HttpStatus.INTERNAL_SERVER_ERROR,
            HttpStatus.NOT_IMPLEMENTED,
            HttpStatus.BAD_REQUEST,
            HttpStatus.UNAUTHORIZED,
            HttpStatus.NOT_FOUND,
            HttpStatus.CONFLICT);

    /** The methods that are never sent again once the service answered. */
    private static final List<HttpMethod> WRITES = List.of(HttpMethod.POST, HttpMethod.PATCH);

    @ParameterizedTest
    @FieldSource("RETRIED")
    @DisplayName("an idempotent call is sent again after 502, 503, 504 or 429")
    void idempotentRetried(HttpStatus status) {
      for (var method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
        assertThat(RetryRules.isRetryable(attempt(method, answer(status)))).isTrue();
      }
    }

    @ParameterizedTest
    @FieldSource("NOT_RETRIED")
    @DisplayName("no call is sent again after any other error answer")
    void otherStatusesNotRetried(HttpStatus status) {
      assertThat(RetryRules.isRetryable(attempt(HttpMethod.GET, answer(status)))).isFalse();
    }

    @ParameterizedTest
    @FieldSource("WRITES")
    @DisplayName("a POST or PATCH is never sent again after an answer, as the service got it")
    void writesNotRetried(HttpMethod method) {
      var attempt = attempt(method, answer(HttpStatus.SERVICE_UNAVAILABLE));

      assertThat(RetryRules.isRetryable(attempt)).isFalse();
    }
  }

  @Nested
  @DisplayName("after no answer")
  class NoAnswer {

    /** The failures to connect, after which nothing was sent. */
    private static final List<IOException> CONNECT_FAILURES =
        List.of(
            new ConnectException("refused"),
            new ConnectTimeoutException("connect timed out"),
            new UnknownHostException("no such host"),
            new NoRouteToHostException("no route"));

    /** The failures after a request may have reached the service. */
    private static final List<IOException> FAILURES_AFTER_SENDING =
        List.of(new SocketTimeoutException("read timed out"), new SocketException("reset"));

    @ParameterizedTest
    @FieldSource("CONNECT_FAILURES")
    @DisplayName("a POST is sent again after a failed connection, as nothing was sent")
    void connectFailureRetriedForPost(IOException cause) {
      assertThat(RetryRules.isRetryable(attempt(HttpMethod.POST, noAnswer(cause)))).isTrue();
    }

    @ParameterizedTest
    @FieldSource("FAILURES_AFTER_SENDING")
    @DisplayName("a timeout or a dropped connection resends a GET but never a POST")
    void failureAfterSendingRetriedOnlyWhenIdempotent(IOException cause) {
      assertThat(RetryRules.isRetryable(attempt(HttpMethod.GET, noAnswer(cause)))).isTrue();
      assertThat(RetryRules.isRetryable(attempt(HttpMethod.POST, noAnswer(cause)))).isFalse();
    }

    @Test
    @DisplayName("a full pool is never retried: it is the service's own load")
    void fullPoolNotRetried() {
      var fullPool = noAnswer(new ConnectionRequestTimeoutException("pool full"));

      assertThat(RetryRules.isRetryable(attempt(HttpMethod.GET, fullPool))).isFalse();
    }
  }

  @Nested
  @DisplayName("never sent again")
  class NeverRetried {

    @Test
    @DisplayName("a call with a streamed body, which cannot be sent twice")
    void streamedBody() {
      var attempt =
          attempt(HttpMethod.GET, answer(HttpStatus.SERVICE_UNAVAILABLE)).toBuilder()
              .streamedBody(true)
              .build();

      assertThat(RetryRules.isRetryable(attempt)).isFalse();
    }

    @Test
    @DisplayName("a call that succeeded")
    void success() {
      assertThat(RetryRules.isRetryable(attempt(HttpMethod.GET, null))).isFalse();
    }

    @Test
    @DisplayName("a call the open breaker refused")
    void openBreaker() {
      var refused =
          CallNotPermittedException.createCallNotPermittedException(
              CircuitBreaker.ofDefaults("inventory"));

      assertThat(RetryRules.isRetryable(attempt(HttpMethod.GET, refused))).isFalse();
    }
  }

  @Nested
  @DisplayName("the time left")
  class TimeLeft {

    @Test
    @DisplayName("another attempt is sent when the wait leaves 100ms for it")
    void enoughTimeLeft() {
      assertThat(RetryRules.shouldRetry(retryableWithTimeLeft(JUST_ENOUGH))).isTrue();
    }

    @Test
    @DisplayName("no attempt is sent when the wait leaves less than 100ms before total-timeout")
    void tooLittleTimeLeft() {
      assertThat(RetryRules.shouldRetry(retryableWithTimeLeft(JUST_ENOUGH.minusMillis(1))))
          .isFalse();
    }
  }

  @Nested
  @DisplayName("the wait before the next attempt")
  class Waits {

    /** The answers whose {@code Retry-After} sets the wait. */
    private static final List<HttpStatus> RETRY_AFTER_STATUSES =
        List.of(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.TOO_MANY_REQUESTS);

    @ParameterizedTest
    @FieldSource("RETRY_AFTER_STATUSES")
    @DisplayName("a 503 or 429 waits for its Retry-After")
    void retryAfterUsed(HttpStatus status) {
      var failure = answer(status, "1");

      assertThat(RetryRules.nextWait(failure, 1, FIXED_BACK_OFF, NOW))
          .isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("a 502's Retry-After is ignored; the back-off sets the wait")
    void retryAfterOfOtherStatusIgnored() {
      var failure = answer(HttpStatus.BAD_GATEWAY, "1");

      assertThat(RetryRules.nextWait(failure, 1, FIXED_BACK_OFF, NOW)).isEqualTo(WAIT);
    }

    @Test
    @DisplayName("without a Retry-After, the back-off sets the wait")
    void backOffWithoutRetryAfter() {
      var failure = noAnswer(new SocketException("reset"));

      assertThat(RetryRules.nextWait(failure, 1, FIXED_BACK_OFF, NOW)).isEqualTo(WAIT);
    }

    @Test
    @DisplayName("the back-off starts at retry-delay, spread by half, and stays at most 2s")
    void backOffGrowsWithinBounds() {
      var backOff = RetryRules.backOff(WAIT);

      assertThat(backOff.apply(1))
          .isBetween(WAIT.toMillis() / 2, WAIT.plus(WAIT.dividedBy(2)).toMillis());
      assertThat(backOff.apply(LATE_ATTEMPT))
          .isLessThanOrEqualTo(HttpClientConstants.MAX_RETRY_DELAY.toMillis());
    }

    @Test
    @DisplayName("a retry-delay above 2s is used as it is, as the longest wait")
    void longRetryDelayKept() {
      var longDelay = HttpClientConstants.MAX_RETRY_DELAY.multipliedBy(2);

      assertThat(RetryRules.backOff(longDelay).apply(LATE_ATTEMPT))
          .isLessThanOrEqualTo(longDelay.toMillis())
          .isGreaterThan(HttpClientConstants.MAX_RETRY_DELAY.toMillis());
    }
  }

  @Nested
  @DisplayName("reading a Retry-After")
  class ParseRetryAfter {

    /** Whole seconds, with and without spaces around them. */
    private static final List<String> WHOLE_SECONDS = List.of("2", " 2 ");

    /**
     * Values that are ignored: missing, empty, negative, not a number or date, ten digits, a past
     * date and one longer than 32 characters.
     */
    private static final List<@Nullable String> IGNORED_VALUES =
        Arrays.asList(
            null,
            "",
            "-1",
            "soon",
            "1234567890",
            "Mon, 09 Nov 2026 11:59:00 GMT",
            "                                 1");

    @ParameterizedTest
    @FieldSource("WHOLE_SECONDS")
    @DisplayName("whole seconds are read")
    void seconds(String value) {
      assertThat(RetryRules.parseRetryAfter(value, NOW)).contains(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("an HTTP date is read as the wait until it")
    void futureDate() {
      var date =
          DateTimeFormatter.RFC_1123_DATE_TIME.format(NOW.plusSeconds(1).atZone(ZoneOffset.UTC));

      assertThat(RetryRules.parseRetryAfter(date, NOW)).contains(Duration.ofSeconds(1));
    }

    @ParameterizedTest
    @FieldSource("IGNORED_VALUES")
    @DisplayName(
        "a value that is missing, negative, not a number or date, too long or past is ignored")
    void ignored(@Nullable String value) {
      assertThat(RetryRules.parseRetryAfter(value, NOW)).isEmpty();
    }
  }

  @Nested
  @DisplayName("what the circuit breaker counts")
  class BreakerFailures {

    @Test
    @DisplayName("no answer, a 5xx and a 429 count; other 4xx do not")
    void counted() {
      assertThat(RetryRules.countsAsBreakerFailure(noAnswer(new SocketException("reset"))))
          .isTrue();
      assertThat(RetryRules.countsAsBreakerFailure(answer(HttpStatus.INTERNAL_SERVER_ERROR)))
          .isTrue();
      assertThat(RetryRules.countsAsBreakerFailure(answer(HttpStatus.TOO_MANY_REQUESTS))).isTrue();
      assertThat(RetryRules.countsAsBreakerFailure(answer(HttpStatus.NOT_FOUND))).isFalse();
      assertThat(RetryRules.countsAsBreakerFailure(new IllegalStateException("bug"))).isFalse();
    }
  }

  /** Returns a retryable attempt whose wait is {@link #WAIT}, with the given time left. */
  private ClientAttempt retryableWithTimeLeft(Duration timeLeft) {
    return attempt(HttpMethod.GET, answer(HttpStatus.SERVICE_UNAVAILABLE)).toBuilder()
        .timeLeft(timeLeft)
        .nextWait(WAIT)
        .build();
  }

  /** Returns an attempt of the method that ended with the failure, with time to spare. */
  private ClientAttempt attempt(HttpMethod method, @Nullable RuntimeException failure) {
    return ClientAttempt.builder()
        .method(method)
        .failure(failure)
        .timeLeft(PLENTY)
        .nextWait(Duration.ZERO)
        .build();
  }

  /** Returns an error answer with the status. */
  private HttpStatusCodeException answer(HttpStatus status) {
    return answer(status, null);
  }

  /** Returns an error answer with the status and, when given, a {@code Retry-After}. */
  private HttpStatusCodeException answer(HttpStatus status, @Nullable String retryAfter) {
    var headers = new HttpHeaders();
    if (retryAfter != null) {
      headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
    }
    return status.is5xxServerError()
        ? HttpServerErrorException.create(
            status, status.getReasonPhrase(), headers, new byte[0], null)
        : HttpClientErrorException.create(
            status, status.getReasonPhrase(), headers, new byte[0], null);
  }

  /** Returns a failure with no answer, caused by the given one. */
  private ResourceAccessException noAnswer(IOException cause) {
    return new ResourceAccessException("I/O error", cause);
  }
}
