package com.exepex.stratum.resilience.testsupport;

import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import lombok.experimental.UtilityClass;

/** Sends requests to a test service while its routes are held, as the integration tests do. */
@UtilityClass
public class RouteRequests {

  /** The longest a test waits for the service. */
  public static final Duration WAIT = Duration.ofSeconds(10);

  /** Sends the requests, each on its own connection, so they run at once. */
  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  /**
   * Sends GET requests to a path at once, without waiting for their answers.
   *
   * @param port the service's port
   * @param path the path
   * @param count how many requests
   * @return the answers to come
   */
  public static List<CompletableFuture<HttpResponse<String>>> sendAtOnce(
      int port, String path, int count) {
    return IntStream.range(0, count).mapToObj(request -> sendLater(port, path)).toList();
  }

  /**
   * Sends one GET request without waiting for its answer.
   *
   * @param port the service's port
   * @param path the path
   * @return the answer to come
   */
  public static CompletableFuture<HttpResponse<String>> sendLater(int port, String path) {
    return HTTP_CLIENT.sendAsync(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /**
   * Fills a route whose limit is 1 with one held request, runs the call while it is full, then lets
   * the held request end and checks that it was answered.
   *
   * @param port the service's port
   * @param heldPath a path of the route whose handler {@link HeldRequests#hold() holds}
   * @param held the service's held work
   * @param call what to run while the route is full
   * @param <T> what the call returns
   * @return what the call returned
   * @throws Exception if the call fails
   */
  public static <T> T whileFull(int port, String heldPath, HeldRequests held, Callable<T> call)
      throws Exception {
    held.close();
    var heldAnswer = sendLater(port, heldPath);
    try {
      await().atMost(WAIT).until(() -> held.running() == 1);
      return call.call();
    } finally {
      held.open();
      heldAnswer.get();
    }
  }
}
