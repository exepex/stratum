package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.HttpClientConstants;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.service.invoker.HttpExchangeAdapter;
import org.springframework.web.service.invoker.HttpRequestValues;

/**
 * Answers a 404 of a call whose method returns {@code Optional} with nothing, which Spring wraps as
 * an empty {@code Optional}. It sits inside the error translation, so no customizer's {@code
 * mapError} is asked about that 404, and outside the retries and breaker, which never count a 404.
 */
@StratumInternal
public class OptionalNotFound implements ClientExchangeDecorator {

  /**
   * Returns the client's calls with a 404 of an {@code Optional} method read as empty.
   *
   * @param client the client's name, which changes nothing
   * @param exchange the client's calls
   * @return the calls with that 404 read as empty
   */
  @Override
  public HttpExchangeAdapter decorate(String client, HttpExchangeAdapter exchange) {
    return new NotFoundAsEmpty(exchange);
  }

  /**
   * Returns the order, just inside the error translation.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return HttpClientConstants.OPTIONAL_NOT_FOUND_ORDER;
  }

  /** The calls of one client, with a 404 of an {@code Optional} method read as empty. */
  private static final class NotFoundAsEmpty extends AroundCallExchangeAdapter {

    /**
     * Makes the adapter.
     *
     * @param exchange the client's calls
     */
    private NotFoundAsEmpty(HttpExchangeAdapter exchange) {
      super(exchange);
    }

    /**
     * Runs one call, and answers a 404 of a call whose method returns {@code Optional} with
     * nothing.
     *
     * @param values the call
     * @param streamedDownload whether the answer's body is read as a stream, which changes nothing
     * @param call the call itself
     * @param <T> what the call returns
     * @return what the call returned, or nothing for that 404
     */
    @Override
    protected <T> @Nullable T around(
        HttpRequestValues values, boolean streamedDownload, Supplier<@Nullable T> call) {
      try {
        return call.get();
      } catch (RestClientResponseException answer) {
        if (answer.getStatusCode().value() == HttpStatus.NOT_FOUND.value()
            && isOptionalAnswer(values)) {
          return null;
        }
        throw answer;
      }
    }
  }
}
