package com.exepex.stratum.restclient.testsupport.timed;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A downstream service that answers every request, on any path, slowly: it waits {@link
 * #answerAfter} before the status line, then sends {@link #FIRST_PART}, waits {@link #pauseBody}
 * and sends {@link #SECOND_PART}. It answers with the status set in {@link #answerWith}, and counts
 * the requests.
 */
public final class SlowDownstream implements AutoCloseable {

  /** The first part of a successful answer's body. */
  public static final String FIRST_PART = "monthly ";

  /** The second part of a successful answer's body. */
  public static final String SECOND_PART = "report";

  /** The server. */
  private final HttpServer server;

  /** Opened by {@link #close()}, so that a paused answer ends at once. */
  private final CountDownLatch closed = new CountDownLatch(1);

  /** How long each answer waits before its status line. */
  private final AtomicReference<Duration> headDelay = new AtomicReference<>(Duration.ZERO);

  /** How long each answer waits between the two parts of its body. */
  private final AtomicReference<Duration> bodyPause = new AtomicReference<>(Duration.ZERO);

  /** The status of each answer. */
  private final AtomicInteger status = new AtomicInteger(HttpURLConnection.HTTP_OK);

  /** The requests so far. */
  private final AtomicInteger requestCount = new AtomicInteger();

  /** Opens the server on the loopback address, with a virtual thread per exchange. */
  private SlowDownstream() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", this::answer);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  /**
   * Starts a server that answers 200 at once.
   *
   * @return the server
   */
  public static SlowDownstream start() {
    try {
      return new SlowDownstream();
    } catch (IOException startFailure) {
      throw new UncheckedIOException(startFailure);
    }
  }

  /**
   * Returns the address to set as a client's {@code url}.
   *
   * @return the address, such as {@code http://127.0.0.1:41234}
   */
  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /**
   * Makes each answer wait this long before its status line, and resets the rest: 200, no pause in
   * the body and no request counted.
   *
   * @param delay the wait
   */
  public void answerAfter(Duration delay) {
    headDelay.set(delay);
    bodyPause.set(Duration.ZERO);
    status.set(HttpURLConnection.HTTP_OK);
    requestCount.set(0);
  }

  /**
   * Makes each answer wait this long between the two parts of its body.
   *
   * @param pause the wait
   */
  public void pauseBody(Duration pause) {
    bodyPause.set(pause);
  }

  /**
   * Makes each answer carry this status.
   *
   * @param answerStatus the status, such as 503
   */
  public void answerWith(int answerStatus) {
    status.set(answerStatus);
  }

  /**
   * Returns the requests since the last {@link #answerAfter}.
   *
   * @return the number of requests
   */
  public int requests() {
    return requestCount.get();
  }

  /** Stops the server at once and ends every paused answer. */
  @Override
  public void close() {
    closed.countDown();
    server.stop(0);
  }

  /** Counts the request and answers it slowly, in two parts. */
  private void answer(HttpExchange exchange) throws IOException {
    try (exchange;
        var requestBody = exchange.getRequestBody()) {
      requestBody.transferTo(OutputStream.nullOutputStream());
      requestCount.incrementAndGet();
      if (!pause(headDelay.get())) {
        return;
      }
      exchange.getResponseHeaders().add("Content-Type", "text/plain");
      // 0 sends the body in chunks, so that its first part arrives before the pause.
      exchange.sendResponseHeaders(status.get(), 0);
      try (var body = exchange.getResponseBody()) {
        body.write(FIRST_PART.getBytes(StandardCharsets.UTF_8));
        body.flush();
        if (pause(bodyPause.get())) {
          body.write(SECOND_PART.getBytes(StandardCharsets.UTF_8));
        }
      }
    }
  }

  /** Waits the time, and returns whether the server is still open. */
  private boolean pause(Duration time) {
    try {
      return !closed.await(time.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
