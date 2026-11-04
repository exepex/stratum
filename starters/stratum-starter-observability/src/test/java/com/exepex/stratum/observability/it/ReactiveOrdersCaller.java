package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;

/** Calls the order endpoint of {@link ReactiveOrdersApplication}, as an outside caller would. */
@RequiredArgsConstructor
class ReactiveOrdersCaller {

  /** The port the service listens on. */
  private final int port;

  /** Sends the requests. */
  private final HttpClient client = HttpClient.newHttpClient();

  /**
   * Builds a request for an order, without headers, so a test can add the ones it needs.
   *
   * @param orderNumber the order number the handler answers with
   * @return the request builder
   */
  HttpRequest.Builder orderRequest(String orderNumber) {
    return HttpRequest.newBuilder(
        URI.create(
            "http://localhost:"
                + port
                + ReactiveOrdersApplication.PLACE_ORDER_PATH
                + "?"
                + ReactiveOrdersApplication.ORDER_NUMBER_PARAMETER
                + "="
                + orderNumber));
  }

  /**
   * Sends one request and checks that the handler answered with the order number.
   *
   * @param request the request
   * @param orderNumber the order number the request carries
   * @throws IOException if the request cannot be sent
   * @throws InterruptedException if the thread is interrupted while waiting for the answer
   */
  void send(HttpRequest request, String orderNumber) throws IOException, InterruptedException {
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.body()).isEqualTo(orderNumber);
  }

  /**
   * Sends one request without waiting for the answer, for tests that send many at once.
   *
   * @param request the request
   * @return the answer, once it arrives
   */
  CompletableFuture<HttpResponse<String>> sendAsync(HttpRequest request) {
    return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
  }
}
