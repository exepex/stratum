package com.exepex.stratum.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A downstream service that the HTTP client starters' tests call on this machine. {@link
 * #STOCK_PATH} answers at once; {@link #HELD_PATH} keeps its connection busy until {@link
 * #releaseHeldRequests} is called or 10 seconds pass, so a test can fill a connection pool or run
 * out a read timeout.
 */
public final class DownstreamStub implements AutoCloseable {

  /** The path that answers at once with {@link #STOCK_BODY}. */
  public static final String STOCK_PATH = "/stock";

  /** The path that answers only once the test releases it. */
  public static final String HELD_PATH = "/held";

  /** The body every answer carries. */
  public static final String STOCK_BODY = "in stock";

  /** The longest a held request waits for the test, so a failed test cannot hang the build. */
  private static final long MAX_HOLD_SECONDS = 10;

  /** The server; it binds to the loopback address on a free port. */
  private final HttpServer server;

  /** Answers each request on its own virtual thread, so held requests block nothing else. */
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  /** Opens once the test releases the held requests. */
  private final CountDownLatch release = new CountDownLatch(1);

  /** How many requests to {@link #HELD_PATH} are waiting now. */
  private final AtomicInteger heldRequestCount = new AtomicInteger();

  /** Makes the stub on a free port of the loopback address. */
  private DownstreamStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setExecutor(executor);
    server.createContext(STOCK_PATH, this::answer);
    server.createContext(HELD_PATH, this::answerWhenReleased);
  }

  /**
   * Starts a stub.
   *
   * @return the running stub; close it when the test ends
   */
  public static DownstreamStub start() {
    try {
      var stub = new DownstreamStub();
      stub.server.start();
      return stub;
    } catch (IOException startFailure) {
      throw new UncheckedIOException(startFailure);
    }
  }

  /**
   * Returns the address the clients under test call, such as {@code http://127.0.0.1:50123}.
   *
   * @return the address, without a trailing slash
   */
  public String baseUrl() {
    var address = server.getAddress();
    return "http://" + address.getHostString() + ":" + address.getPort();
  }

  /**
   * Returns how many requests to {@link #HELD_PATH} are waiting now.
   *
   * @return the number of held requests
   */
  public int heldRequests() {
    return heldRequestCount.get();
  }

  /** Lets every held request, waiting now or arriving later, answer. */
  public void releaseHeldRequests() {
    release.countDown();
  }

  /** Releases the held requests and stops the stub. */
  @Override
  public void close() {
    releaseHeldRequests();
    server.stop(0);
    executor.close();
  }

  /**
   * Answers a request once the test releases it, or with 503 after the longest hold, so a test that
   * forgot to release sees why.
   */
  private void answerWhenReleased(HttpExchange exchange) throws IOException {
    heldRequestCount.incrementAndGet();
    var released = false;
    try {
      released = release.await(MAX_HOLD_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      heldRequestCount.decrementAndGet();
    }
    answer(exchange, released ? HttpURLConnection.HTTP_OK : HttpURLConnection.HTTP_UNAVAILABLE);
  }

  /** Answers a request with 200 and {@link #STOCK_BODY}. */
  private void answer(HttpExchange exchange) throws IOException {
    answer(exchange, HttpURLConnection.HTTP_OK);
  }

  /** Answers a request with the status and {@link #STOCK_BODY}. */
  private void answer(HttpExchange exchange, int status) throws IOException {
    var body = STOCK_BODY.getBytes(StandardCharsets.UTF_8);
    try (exchange) {
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
    }
  }
}
