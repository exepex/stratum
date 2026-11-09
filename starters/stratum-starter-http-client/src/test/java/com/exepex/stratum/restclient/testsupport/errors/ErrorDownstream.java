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
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/**
 * A downstream service that answers with error bodies, for the error tests. It answers by path:
 *
 * <ul>
 *   <li>{@link #PROBLEM}: 404, a problem detail with the code {@link #DOWNSTREAM_CODE};
 *   <li>{@link #BROKEN_PROBLEM}: 404, typed as a problem detail but not JSON;
 *   <li>{@link #FAILED}: 500, a text body that holds {@link RestClientTestData#SECRET};
 *   <li>{@link #LARGE_ERROR}: 500, {@link #LARGE_SIZE} bytes;
 *   <li>{@link #LARGE_GZIP_ERROR}: 500, {@link #LARGE_SIZE} bytes once gzip is undone;
 *   <li>{@link #LARGE_ANSWER}: 200, {@link #LARGE_SIZE} bytes.
 * </ul>
 */
public final class ErrorDownstream implements AutoCloseable {

  /** The path of a 404 problem detail. */
  public static final String PROBLEM = "/problem";

  /** The path of a 404 typed as a problem detail that is not JSON. */
  public static final String BROKEN_PROBLEM = "/broken-problem";

  /** The start of the path of a 500 whose body holds a secret. */
  public static final String FAILED = "/failed";

  /** The path of a large error body. */
  public static final String LARGE_ERROR = "/large-error";

  /** The path of a large gzip error body. */
  public static final String LARGE_GZIP_ERROR = "/large-gzip-error";

  /** The path of a large body that succeeds. */
  public static final String LARGE_ANSWER = "/large-answer";

  /** The code in the {@link #PROBLEM} body. */
  public static final String DOWNSTREAM_CODE = "ORD-0001";

  /** The size of each large body, 1 MB, far over the 64 KB read of an error body. */
  public static final int LARGE_SIZE = 1024 * 1024;

  /** The content type of a text body. */
  private static final String TEXT = "text/plain";

  /** The problem detail's content type. */
  private static final String PROBLEM_JSON = "application/problem+json";

  /** The server. */
  private final HttpServer server;

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
    } catch (IOException e) {
      throw new UncheckedIOException(e);
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
