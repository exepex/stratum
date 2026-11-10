package com.exepex.stratum.restclient.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.springframework.http.HttpHeaders;

/**
 * A downstream service whose answer changes from one attempt to the next: each request, on any
 * path, gets the next status a test queued, and 200 with {@link ScriptedDownstream#BODY} once none
 * is left. Every answer carries its number in {@link #ANSWER_NUMBER} and, for an error, in its
 * body, so a test sees which attempt's answer it got. It records each request's method, path and
 * headers, and reads and drops its body.
 */
public final class QueuedDownstream implements AutoCloseable {

  /** The header that holds the answer's number, from 1. */
  public static final String ANSWER_NUMBER = "X-Answer-Number";

  /** The start of an error answer's body, followed by its number, such as {@code answer 3}. */
  public static final String ERROR_BODY = "answer ";

  /** The server. */
  private final HttpServer server;

  /** The statuses still to answer with, in order. */
  private final Queue<Integer> statuses = new ConcurrentLinkedQueue<>();

  /** Each request's method and path, such as {@code GET /stock}, in order. */
  private final List<String> received = new CopyOnWriteArrayList<>();

  /** Each request's headers, in order. */
  private final List<HttpHeaders> receivedHeaders = new CopyOnWriteArrayList<>();

  /** Opens the server on the loopback address, with a virtual thread per exchange. */
  private QueuedDownstream() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", this::answer);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  /**
   * Starts a server.
   *
   * @return the server
   */
  public static QueuedDownstream start() {
    try {
      return new QueuedDownstream();
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
   * Sets the statuses the next requests get, in order, in place of any still queued; later requests
   * get 200.
   *
   * @param nextStatuses the statuses, such as 503, 503
   */
  public void answerNext(int... nextStatuses) {
    statuses.clear();
    Arrays.stream(nextStatuses).forEach(statuses::add);
  }

  /**
   * Returns each request's method and path so far, such as {@code PUT /stock}.
   *
   * @return the requests, in order
   */
  public List<String> requests() {
    return List.copyOf(received);
  }

  /**
   * Returns the value of one header in each request so far, or an empty text where a request did
   * not send it.
   *
   * @param name the header's name, such as Authorization
   * @return the values, in order
   */
  public List<String> headerValues(String name) {
    return receivedHeaders.stream()
        .map(headers -> Optional.ofNullable(headers.getFirst(name)).orElse(""))
        .toList();
  }

  /** Stops the server at once. */
  @Override
  public void close() {
    server.stop(0);
  }

  /** Records the request and answers it with the next queued status, or 200. */
  private void answer(HttpExchange exchange) throws IOException {
    try (exchange;
        var requestBody = exchange.getRequestBody()) {
      requestBody.transferTo(OutputStream.nullOutputStream());
      var headers = new HttpHeaders();
      exchange.getRequestHeaders().forEach(headers::addAll);
      receivedHeaders.add(headers);
      received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
      var number = received.size();
      var status = Optional.ofNullable(statuses.poll()).orElse(HttpURLConnection.HTTP_OK);
      var body =
          (status == HttpURLConnection.HTTP_OK ? ScriptedDownstream.BODY : ERROR_BODY + number)
              .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, "text/plain");
      exchange.getResponseHeaders().add(ANSWER_NUMBER, String.valueOf(number));
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
    }
  }
}
