package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.web.service.invoker.HttpExchangeAdapter;
import org.springframework.web.service.invoker.HttpRequestValues;

/**
 * Runs every call of one client's interface through the client's retries and circuit breaker
 * ({@link ClientResilience}).
 */
@StratumInternal
public class ResilientExchangeAdapter extends AroundCallExchangeAdapter {

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
   * Runs one call of the client through its retries and breaker.
   *
   * @param values the call
   * @param streamedDownload whether the answer's body is read as a stream
   * @param call the call itself
   * @param <T> what the call returns
   * @return what the last attempt returned
   */
  @Override
  protected <T> @Nullable T around(
      HttpRequestValues values, boolean streamedDownload, Supplier<@Nullable T> call) {
    return resilience.call(
        ClientCall.builder()
            .client(client)
            .method(Objects.requireNonNull(values.getHttpMethod()))
            .uriTemplate(values.getUriTemplate())
            .streamedBody(StreamedBody.isStreamed(values.getBodyValue()))
            .streamedDownload(streamedDownload)
            .notFoundIsEmpty(isOptionalAnswer(values))
            .build(),
        call);
  }
}
