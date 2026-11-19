package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.FailureCauses;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;
import org.springframework.web.service.invoker.HttpExchangeAdapter;
import org.springframework.web.service.invoker.HttpRequestValues;

/**
 * Turns the failure that ends a call of a client's interface, after its retries, into what the
 * service sees: an error answer becomes a {@link DownstreamException}, or the team's own exception
 * thrown in {@link StratumClientCustomizer#mapError}; no answer becomes {@code SX-HTTPC-0001} after
 * a read timeout and {@code SX-HTTPC-0002} otherwise; an answer larger than {@code
 * response-body-limit} becomes {@code SX-HTTPC-0006}; an answer whose body cannot be read becomes
 * an {@link UnreadableAnswerException}. Any other failure, such as a request body no converter can
 * write, stays as it is. It is the outermost of Stratum's decorators, so the retries and breaker
 * see Spring's own exceptions.
 */
@StratumInternal
@RequiredArgsConstructor
public class ClientErrors implements ClientExchangeDecorator {

  /** The service's changes to the clients, which may map an error answer to its own exception. */
  private final ObjectProvider<StratumClientCustomizer> customizers;

  /**
   * Returns the client's calls with their failures translated.
   *
   * @param client the client's name
   * @param exchange the client's calls
   * @return the calls with their failures translated
   */
  @Override
  public HttpExchangeAdapter decorate(String client, HttpExchangeAdapter exchange) {
    return new ErrorTranslatingExchangeAdapter(exchange, client, this);
  }

  /**
   * Returns the order of the translation, the outermost of Stratum's decorators.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return HttpClientConstants.ERROR_TRANSLATION_ORDER;
  }

  /**
   * Returns the exception of a call that ended with an error answer, after the service's
   * customizers had their turn: the first that throws its own exception in {@link
   * StratumClientCustomizer#mapError} replaces it.
   *
   * @param client the client's name
   * @param values the call
   * @param answer Spring's exception for the error answer
   * @return the exception to throw when no customizer threw its own
   */
  public DownstreamException downstream(
      String client, HttpRequestValues values, RestClientResponseException answer) {
    var downstream = new DownstreamException(client, endpointOf(values), answer);
    customizers.orderedStream().forEach(customizer -> customizer.mapError(client, downstream));
    return downstream;
  }

  /**
   * Returns the code of a call that got no answer, with the failure as its cause and no detail, so
   * that the caller is told nothing about the other service.
   *
   * @param failure the failure that ended the call
   * @return the code's exception, or empty when the call got an answer, such as one whose body is
   *     not valid
   */
  public Optional<StratumException> noAnswer(RestClientException failure) {
    return RetryRules.ioFailureOf(failure)
        .map(cause -> new StratumException(codeFor(cause), "", failure));
  }

  /**
   * Returns {@code SX-HTTPC-0006} for a call whose answer's body passed the client's {@code
   * response-body-limit}, with that failure as its cause and no detail.
   *
   * @param failure the failure that ended the call
   * @return the code's exception, or empty when the body was within the limit
   */
  public Optional<StratumException> tooLarge(RestClientException failure) {
    return FailureCauses.causesOf(failure)
        .filter(ResponseBodyTooLarge.class::isInstance)
        .findFirst()
        .map(cause -> new StratumException(HttpClientErrorCode.RESPONSE_TOO_LARGE, "", cause));
  }

  /**
   * Returns the exception of a call whose answer came but cannot be read: a content type that no
   * converter reads, or Spring's failure to extract the body, whose cause is the converter's {@code
   * HttpMessageNotReadableException} or an {@code IOException} that is not a lost connection, which
   * {@link #noAnswer} takes first. A request body that could not be written fails with no such
   * cause, and nothing was sent.
   *
   * @param client the client's name
   * @param values the call
   * @param failure the failure that ended the call
   * @return the exception, or empty when the failure is not an answer that cannot be read
   */
  public Optional<UnreadableAnswerException> unreadable(
      String client, HttpRequestValues values, RestClientException failure) {
    return Optional.of(failure)
        .filter(this::isUnreadableAnswer)
        .map(answer -> new UnreadableAnswerException(client, endpointOf(values), answer));
  }

  /** Returns whether the failure is one of Spring's failures to read an answer's body. */
  private boolean isUnreadableAnswer(RestClientException failure) {
    return failure instanceof UnknownContentTypeException
        || failure.getCause() instanceof HttpMessageNotReadableException
        || failure.getCause() instanceof IOException;
  }

  /**
   * Returns the code of a call that got no answer: a timeout once connected, or unavailable for a
   * failure to connect, which includes a connect timeout, a full pool and a dropped connection.
   */
  private HttpClientErrorCode codeFor(IOException cause) {
    return RetryRules.isAnswerTimeout(cause)
        ? HttpClientErrorCode.DOWNSTREAM_TIMEOUT
        : HttpClientErrorCode.DOWNSTREAM_UNAVAILABLE;
  }

  /**
   * Returns the call's method and URI template, such as {@code GET /stock/{sku}}, or its method
   * alone when it was given a whole URI, whose path holds values; never its query, which Spring
   * adds to the template.
   */
  private String endpointOf(HttpRequestValues values) {
    var method = Objects.requireNonNull(values.getHttpMethod()).name();
    return Optional.ofNullable(values.getUriTemplate())
        .map(template -> template.split("\\?", 2)[0])
        .filter(path -> !path.isEmpty())
        .map(path -> method + " " + path)
        .orElse(method);
  }
}
