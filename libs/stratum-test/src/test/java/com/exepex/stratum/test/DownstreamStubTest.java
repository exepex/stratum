package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DownstreamStub answers at once, or holds a request until the test releases it")
class DownstreamStubTest {

  /** The longest a test waits for the stub. */
  private static final Duration WAIT = Duration.ofSeconds(5);

  /** The stub under test. */
  private final DownstreamStub stub = DownstreamStub.start();

  /** Calls the stub. */
  private final HttpClient httpClient = HttpClient.newHttpClient();

  /** Stops the stub and the client after each test. */
  @AfterEach
  void stopStub() {
    stub.close();
    httpClient.close();
  }

  @Test
  @DisplayName("the stock path answers at once")
  void answersStock() throws Exception {
    var response =
        httpClient.send(request(DownstreamStub.STOCK_PATH), HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(HttpURLConnection.HTTP_OK);
    assertThat(response.body()).isEqualTo(DownstreamStub.STOCK_BODY);
  }

  @Test
  @DisplayName("the held path answers only once released, and counts the requests it holds")
  void holdsUntilReleased() {
    var response =
        httpClient.sendAsync(
            request(DownstreamStub.HELD_PATH), HttpResponse.BodyHandlers.ofString());
    await().atMost(WAIT).until(() -> stub.heldRequests() == 1);

    assertThat(response).isNotDone();

    stub.releaseHeldRequests();
    assertThat(response)
        .succeedsWithin(WAIT)
        .extracting(HttpResponse::body)
        .isEqualTo(DownstreamStub.STOCK_BODY);
    await().atMost(WAIT).until(() -> stub.heldRequests() == 0);
  }

  /** Returns a request for a path of the stub. */
  private HttpRequest request(String path) {
    return HttpRequest.newBuilder(URI.create(stub.baseUrl() + path)).build();
  }
}
