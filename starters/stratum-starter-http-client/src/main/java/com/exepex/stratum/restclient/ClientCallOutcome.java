package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import java.net.URI;
import java.time.Duration;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * How one call of a Stratum client ended, after its retries: what its log line writes and its
 * meters count.
 *
 * @param call the call
 * @param outcome how it ended
 * @param status the status of its error answer, or {@code null} when it ended without one
 * @param url the address its last sent attempt went to, or {@code null} when none was sent
 * @param attempts the attempts the open breaker did not refuse
 * @param duration the time the whole call took, retries and waits included
 */
@StratumInternal
@Builder
public record ClientCallOutcome(
    ClientCall call,
    CallOutcome outcome,
    @Nullable Integer status,
    @Nullable URI url,
    int attempts,
    Duration duration) {

  /**
   * Returns how a call ended, from its last attempt the breaker did not refuse.
   *
   * @param call the call
   * @param lastSent the last attempt the breaker did not refuse, or {@code null} when it refused
   *     every attempt
   * @param notSent the outcome of a failure that ended the call before its attempts did, such as a
   *     service token that could not be fetched, or {@code null} when the attempts ended it
   * @param attempts the attempts the breaker did not refuse
   * @param duration the time the whole call took
   * @return how the call ended
   */
  public static ClientCallOutcome of(
      ClientCall call,
      @Nullable ClientAttempt lastSent,
      @Nullable CallOutcome notSent,
      int attempts,
      Duration duration) {
    var ended =
        ClientCallOutcome.builder()
            .call(call)
            .url(lastSent == null ? null : lastSent.getUrl())
            .attempts(attempts)
            .duration(duration);
    if (notSent != null) {
      return ended.outcome(notSent).build();
    }
    if (lastSent == null) {
      return ended.outcome(CallOutcome.REFUSED).build();
    }
    return switch (lastSent.getFailure().orElse(null)) {
      case null -> ended.outcome(CallOutcome.SUCCESS).build();
      case RestClientResponseException answer when isEmptyAnswer(call, answer) ->
          ended.outcome(CallOutcome.SUCCESS).build();
      case RestClientResponseException answer ->
          ended.outcome(answerOutcome(answer)).status(answer.getStatusCode().value()).build();
      case RestClientException failed -> ended.outcome(noAnswerOutcome(failed)).build();
      default -> ended.outcome(CallOutcome.ERROR).build();
    };
  }

  /** Returns whether an error answer is a 404 that the call reads as nothing. */
  private static boolean isEmptyAnswer(ClientCall call, RestClientResponseException answer) {
    return call.notFoundIsEmpty() && answer.getStatusCode().value() == HttpStatus.NOT_FOUND.value();
  }

  /** Returns the outcome of an error answer: by its status class, or an error for any other. */
  private static CallOutcome answerOutcome(RestClientResponseException answer) {
    if (answer.getStatusCode().is4xxClientError()) {
      return CallOutcome.CLIENT_ERROR;
    }
    return answer.getStatusCode().is5xxServerError() ? CallOutcome.SERVER_ERROR : CallOutcome.ERROR;
  }

  /**
   * Returns the outcome of a failure with no error answer: a timeout or unavailable when an I/O
   * failure ended it, or an error inside the service, such as an answer that cannot be read.
   */
  private static CallOutcome noAnswerOutcome(RestClientException failure) {
    return RetryRules.ioFailureOf(failure)
        .map(
            cause ->
                RetryRules.isAnswerTimeout(cause) ? CallOutcome.TIMEOUT : CallOutcome.UNAVAILABLE)
        .orElse(CallOutcome.ERROR);
  }

  /** Builds the outcome; Lombok writes its methods, the label keeps it internal. */
  @StratumInternal
  public static class ClientCallOutcomeBuilder {}
}
