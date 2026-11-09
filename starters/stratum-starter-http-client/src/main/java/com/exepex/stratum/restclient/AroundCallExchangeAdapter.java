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
 * Runs every kind of call of a client's interface through one method, {@link #around}, so that a
 * decorator such as the retries handles each call in one place. A call that returns an {@code
 * InputStream} is a streamed download.
 */
@StratumInternal
public abstract class AroundCallExchangeAdapter extends HttpExchangeAdapterDecorator {

  /**
   * Makes the adapter.
   *
   * @param exchange the client's calls
   */
  protected AroundCallExchangeAdapter(HttpExchangeAdapter exchange) {
    super(exchange);
  }

  /**
   * Runs one call of the client.
   *
   * @param values the call
   * @param streamedDownload whether the answer's body is read as a stream
   * @param call the call itself
   * @param <T> what the call returns
   * @return what the call returned
   */
  protected abstract <T> @Nullable T around(
      HttpRequestValues values, boolean streamedDownload, Supplier<@Nullable T> call);

  /**
   * Sends a call that returns nothing.
   *
   * @param values the call
   */
  @Override
  public void exchange(HttpRequestValues values) {
    around(values, false, () -> sendWithoutAnswer(values));
  }

  /**
   * Sends a call that returns the answer's headers.
   *
   * @param values the call
   * @return the headers
   */
  @Override
  public HttpHeaders exchangeForHeaders(HttpRequestValues values) {
    return Objects.requireNonNull(around(values, false, () -> super.exchangeForHeaders(values)));
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
    return around(values, isStream(bodyType), () -> super.exchangeForBody(values, bodyType));
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
        around(values, false, () -> super.exchangeForBodilessEntity(values)));
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
        around(values, isStream(bodyType), () -> super.exchangeForEntity(values, bodyType)));
  }

  /** Sends a call that returns nothing, as a call that returns no value. */
  private @Nullable Void sendWithoutAnswer(HttpRequestValues values) {
    super.exchange(values);
    return null;
  }

  /** Returns whether the answer's body is read as a stream, a streamed download. */
  private boolean isStream(ParameterizedTypeReference<?> bodyType) {
    return bodyType.getType() == InputStream.class;
  }
}
