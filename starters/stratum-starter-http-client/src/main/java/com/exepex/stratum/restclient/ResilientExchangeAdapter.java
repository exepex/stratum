package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.service.invoker.HttpExchangeAdapter;
import org.springframework.web.service.invoker.HttpExchangeAdapterDecorator;
import org.springframework.web.service.invoker.HttpRequestValues;

/**
 * Runs every call of one client's interface through the client's retries and circuit breaker
 * ({@link ClientResilience}). A call that returns an {@code InputStream} is a streamed download.
 */
@StratumInternal
public class ResilientExchangeAdapter extends HttpExchangeAdapterDecorator {

  /** The client's name. */
  private final String client;

  /** The retries and breakers of every client. */
  private final ClientResilience resilience;

  /**
   * Makes the adapter.
   *
   * @param exchange the client's calls
   * @param client the client's name
   * @param resilience the retries and breakers of every client
   */
  public ResilientExchangeAdapter(
      HttpExchangeAdapter exchange, String client, ClientResilience resilience) {
    super(exchange);
    this.client = client;
    this.resilience = resilience;
  }

  /**
   * Sends a call that returns nothing.
   *
   * @param values the call
   */
  @Override
  public void exchange(HttpRequestValues values) {
    run(values, false, () -> sendWithoutAnswer(values));
  }

  /**
   * Sends a call that returns the answer's headers.
   *
   * @param values the call
   * @return the headers
   */
  @Override
  public HttpHeaders exchangeForHeaders(HttpRequestValues values) {
    return Objects.requireNonNull(run(values, false, () -> super.exchangeForHeaders(values)));
  }

  /**
   * Sends a call that returns the answer's body.
   *
   * @param values the call
   * @param bodyType the body's type
   * @param <T> the body's type
   * @return the body
   */
  @Override
  public <T> @Nullable T exchangeForBody(
      HttpRequestValues values, ParameterizedTypeReference<T> bodyType) {
    return run(values, isStream(bodyType), () -> super.exchangeForBody(values, bodyType));
  }

  /**
   * Sends a call that returns the answer without a body.
   *
   * @param values the call
   * @return the answer
   */
  @Override
  public ResponseEntity<Void> exchangeForBodilessEntity(HttpRequestValues values) {
    return Objects.requireNonNull(
        run(values, false, () -> super.exchangeForBodilessEntity(values)));
  }

  /**
   * Sends a call that returns the whole answer.
   *
   * @param values the call
   * @param bodyType the body's type
   * @param <T> the body's type
   * @return the answer
   */
  @Override
  public <T> ResponseEntity<T> exchangeForEntity(
      HttpRequestValues values, ParameterizedTypeReference<T> bodyType) {
    return Objects.requireNonNull(
        run(values, isStream(bodyType), () -> super.exchangeForEntity(values, bodyType)));
  }

  /** Runs one call of the client through its retries and breaker. */
  private <T> @Nullable T run(
      HttpRequestValues values, boolean streamedDownload, Supplier<@Nullable T> exchange) {
    return resilience.call(
        client,
        values.getHttpMethod(),
        StreamedBody.isStreamed(values.getBodyValue()),
        streamedDownload,
        exchange);
  }

  /** Sends a call that returns nothing, as one attempt that returns no value. */
  private @Nullable Void sendWithoutAnswer(HttpRequestValues values) {
    super.exchange(values);
    return null;
  }

  /** Returns whether the answer's body is read as a stream, a streamed download. */
  private boolean isStream(ParameterizedTypeReference<?> bodyType) {
    return bodyType.getType() == InputStream.class;
  }
}
