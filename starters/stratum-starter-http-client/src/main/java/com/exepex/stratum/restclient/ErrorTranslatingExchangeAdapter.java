package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.service.invoker.HttpExchangeAdapter;
import org.springframework.web.service.invoker.HttpRequestValues;

/**
 * Translates the failure that ends each call of one client's interface, with {@link ClientErrors}.
 */
@StratumInternal
public class ErrorTranslatingExchangeAdapter extends AroundCallExchangeAdapter {

  /** The client's name. */
  private final String client;

  /** The translation of a call's failure. */
  private final ClientErrors errors;

  /**
   * Makes the adapter.
   *
   * @param exchange the client's calls
   * @param client the client's name
   * @param errors the translation of a call's failure
   */
  public ErrorTranslatingExchangeAdapter(
      HttpExchangeAdapter exchange, String client, ClientErrors errors) {
    super(exchange);
    this.client = client;
    this.errors = errors;
  }

  /**
   * Runs one call and throws its failure as the service sees it.
   *
   * @param values the call
   * @param streamedDownload whether the answer's body is read as a stream, which changes nothing
   * @param call the call itself
   * @param <T> what the call returns
   * @return what the call returned
   */
  @Override
  protected <T> @Nullable T around(
      HttpRequestValues values, boolean streamedDownload, Supplier<@Nullable T> call) {
    try {
      return call.get();
    } catch (RestClientResponseException answer) {
      throw errors.downstream(client, values, answer);
    } catch (RestClientException failure) {
      throw errors.noAnswer(failure).orElseThrow(() -> failure);
    }
  }
}
