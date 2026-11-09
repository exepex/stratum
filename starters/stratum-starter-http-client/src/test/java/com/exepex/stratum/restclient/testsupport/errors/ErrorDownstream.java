package com.exepex.stratum.restclient.testsupport.errors;

import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * A downstream service that answers with error bodies, for the error tests. It answers by path:
 *
 * <ul>
 *   <li>{@link #PROBLEM}: 404, a problem detail with the code {@link #DOWNSTREAM_CODE};
 *   <li>{@link #BROKEN_PROBLEM}: 404, typed as a problem detail but not JSON;
 *   <li>{@link #FORBIDDEN}: 403, with no body;
 *   <li>{@link #FAILED}: 500, a text body that holds {@link RestClientTestData#SECRET};
 *   <li>{@link #LARGE_ERROR}: 500, {@link #LARGE_SIZE} bytes;
 *   <li>{@link #LARGE_GZIP_ERROR}: 500, {@link #LARGE_SIZE} bytes once gzip is undone;
 *   <li>{@link #LARGE_ANSWER}: 200, {@link #LARGE_SIZE} bytes;
 *   <li>{@link #CUT_JSON}: 200, the start of a JSON body, then nothing until the server closes.
 * </ul>
 */
public final class ErrorDownstream implements AutoCloseable {

  /** The path of a 404 problem detail. */
  public static final String PROBLEM = "/problem";

  /** The path of a 404 typed as a problem detail that is not JSON. */
  public static final String BROKEN_PROBLEM = "/broken-problem";

  /** The path of a 403. */
  public static final String FORBIDDEN = "/forbidden";

  /** The start of the path of a 500 whose body holds a secret. */
  public static final String FAILED = "/failed";

  /** The path of a large error body. */
  public static final String LARGE_ERROR = "/large-error";

  /** The path of a large gzip error body. */
  public static final String LARGE_GZIP_ERROR = "/large-gzip-error";

  /** The path of a large body that succeeds. */
  public static final String LARGE_ANSWER = "/large-answer";

  /** The path of a JSON answer whose body stops after its first bytes. */
  public static final String CUT_JSON = "/cut-json";

  /** The code in the {@link #PROBLEM} body. */
  public static final String DOWNSTREAM_CODE = "ORD-0001";

  /** The size of each large body, 1 MB, far over the 64 KB read of an error body. */
  public static final int LARGE_SIZE = 1024 * 1024;

  /** The length a {@link #CUT_JSON} answer promises, far more than it sends. */
  private static final int CUT_JSON_LENGTH = 1000;

  /** The content type of a text body. */
  private static final String TEXT = "text/plain";

  /** The problem detail's content type. */
  private static final String PROBLEM_JSON = "application/problem+json";

  /** The server. */
  private final HttpServer server;

  /** Opened by {@link #close()}, so that a {@link #CUT_JSON} answer ends at once. */
  private final CountDownLatch closed = new CountDownLatch(1);

  /** The client port of each connection, one per connection opened. */
  private final Set<Integer> clientPorts = ConcurrentHashMap.newKeySet();

  /** Opens the server on the loopback address, with a virtual thread per exchange. */
  private ErrorDownstream() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    var problem = "{\"title\":\"Not Found\",\"status\":404,\"code\":\"" + DOWNSTREAM_CODE + "\"}";
    server.createContext(
        PROBLEM,
        exchange -> send(exchange, HttpURLConnection.HTTP_NOT_FOUND, PROBLEM_JSON, bytes(problem)));
    server.createContext(
        BROKEN_PROBLEM,
        exchange ->
            send(exchange, HttpURLConnection.HTTP_NOT_FOUND, PROBLEM_JSON, bytes("{not json")));
    server.createContext(
        FORBIDDEN, exchange -> send(exchange, HttpURLConnection.HTTP_FORBIDDEN, TEXT, new byte[0]));
    server.createContext(
        FAILED,
        exchange ->
            send(
                exchange,
                HttpURLConnection.HTTP_INTERNAL_ERROR,
                "text/plain",
                bytes("card " + RestClientTestData.SECRET)));
    server.createContext(
        LARGE_ERROR,
        exchange -> send(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR, TEXT, large()));
    server.createContext(LARGE_GZIP_ERROR, this::sendLargeGzip);
    server.createContext(CUT_JSON, this::sendCutJson);
    server.createContext(
        LARGE_ANSWER, exchange -> send(exchange, HttpURLConnection.HTTP_OK, TEXT, large()));
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  /**
   * Starts a server.
   *
   * @return the server
   */
  public static ErrorDownstream start() {
    try {
      return new ErrorDownstream();
    } catch (IOException startFailure) {
      throw new UncheckedIOException(startFailure);
    }
  }

  /**
   * Returns the address to set as a client's {@code url}.
   *
   * @return the address
   */
  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /**
   * Returns how many connections the clients opened so far.
   *
   * @return the count
   */
  public int connections() {
    return clientPorts.size();
  }

  @Override
  public void close() {
    closed.countDown();
    server.stop(0);
  }

  /** Sends an answer with its whole body and a length. */
  private void send(HttpExchange exchange, int status, String contentType, byte[] body)
      throws IOException {
    clientPorts.add(exchange.getRemoteAddress().getPort());
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(status, body.length);
    try (var out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  /** Sends a 500 whose 1 MB body is compressed with gzip. */
  private void sendLargeGzip(HttpExchange exchange) throws IOException {
    var compressed = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(compressed)) {
      gzip.write(large());
    }
    exchange.getResponseHeaders().add("Content-Encoding", "gzip");
    send(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR, TEXT, compressed.toByteArray());
  }

  /**
   * Sends a 200 that promises a JSON body, sends its first bytes and then nothing more until the
   * server closes, far longer than the client's timeout.
   */
  private void sendCutJson(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, CUT_JSON_LENGTH);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes("{\"id\":\"o-1\","));
      out.flush();
      awaitClose();
    }
  }

  /** Waits until the server closes, at most {@link RestClientTestData#WAIT}. */
  private void awaitClose() {
    try {
      closed.await(RestClientTestData.WAIT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  /** Returns a text as bytes. */
  private byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /** Returns 1 MB of text. */
  private byte[] large() {
    var body = new byte[LARGE_SIZE];
    Arrays.fill(body, (byte) 'x');
    return body;
  }
}
