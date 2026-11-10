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
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * A downstream service whose answers are larger than a client's limit, or never end, for the tests
 * of {@code response-body-limit}. It counts the connections it served, by the client's port, and
 * the requests of each path. It answers by path, with a size in bytes after the paths that take
 * one:
 *
 * <ul>
 *   <li>{@link #SIZED}: 200 with that many bytes and their {@code Content-Length};
 *   <li>{@link #CHUNKED}: 200 with that many bytes, starting with {@link #MARKER}, chunked;
 *   <li>{@link #GZIPPED}: 200 with that many spaces, compressed with gzip;
 *   <li>{@link #TRAILING}: 200 with {@link #JSON_VALUE} and then that many spaces;
 *   <li>{@link #ENDLESS}: 200 with spaces that never end;
 *   <li>{@link #STALLED}: 200 with a {@code Content-Length} of 50 GB, then nothing.
 * </ul>
 */
public final class LargeAnswerDownstream implements AutoCloseable {

  /** The end of a client's paths that take a size: the variable that holds it. */
  public static final String SIZE = "{size}";

  /** The start of the paths of a body of the size that follows, with its length. */
  public static final String SIZED = "/sized/";

  /** The start of the paths of a chunked body of the size that follows. */
  public static final String CHUNKED = "/chunked/";

  /** The start of the paths of a gzip body of the size that follows, once decoded. */
  public static final String GZIPPED = "/gzipped/";

  /** The start of the paths of a JSON value followed by the number of spaces that follows. */
  public static final String TRAILING = "/trailing/";

  /** The path of a body that never ends. */
  public static final String ENDLESS = "/endless";

  /** The path of a body announced as 50 GB, of which nothing comes. */
  public static final String STALLED = "/stalled";

  /** The text a {@link #CHUNKED} body starts with, which no message may show. */
  public static final String MARKER = "secret-body-7c1d";

  /** The JSON value a {@link #TRAILING} body starts with. */
  public static final String JSON_VALUE = "{\"stock\":1}";

  /** The name of the header that gives a body's type. */
  private static final String CONTENT_TYPE = "Content-Type";

  /** The type of a JSON body. */
  private static final String JSON = "application/json";

  /** The type of a body of raw bytes. */
  private static final String OCTETS = "application/octet-stream";

  /** The length a stalled answer announces: 50 GB. */
  private static final long ANNOUNCED_LENGTH = 50L * 1024 * 1024 * 1024;

  /** The size of each write of an endless body. */
  private static final int CHUNK = 8192;

  /** How long a stalled answer waits at most, far above any timeout a test sets. */
  private static final Duration STALL_FOR = Duration.ofSeconds(20);

  /** The server. */
  private final HttpServer server;

  /** Opened by {@link #close()}, so that a stalled answer ends at once. */
  private final CountDownLatch closed = new CountDownLatch(1);

  /** The client ports of every connection served so far. */
  private final Set<Integer> clientPorts = ConcurrentHashMap.newKeySet();

  /** The path of every request, in order. */
  private final List<String> receivedPaths = new CopyOnWriteArrayList<>();

  /** Opens the server on the loopback address, with a virtual thread per exchange. */
  private LargeAnswerDownstream() throws IOException {
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
  public static LargeAnswerDownstream start() {
    try {
      return new LargeAnswerDownstream();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  /**
   * Returns the server's address, such as {@code http://127.0.0.1:43211}.
   *
   * @return the address
   */
  public String url() {
    return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
  }

  /**
   * Returns the number of connections served so far.
   *
   * @return the number of connections
   */
  public int connections() {
    return clientPorts.size();
  }

  /**
   * Returns how many requests a path got.
   *
   * @param path the path, such as {@code /sized/100}
   * @return the number of requests
   */
  public long requests(String path) {
    return receivedPaths.stream().filter(path::equals).count();
  }

  @Override
  public void close() {
    closed.countDown();
    server.stop(0);
  }

  /** Answers one request by its path. */
  private void answer(HttpExchange exchange) throws IOException {
    clientPorts.add(exchange.getRemoteAddress().getPort());
    var path = exchange.getRequestURI().getPath();
    receivedPaths.add(path);
    try (exchange) {
      switch (path) {
        case ENDLESS -> sendEndless(exchange);
        case STALLED -> stall(exchange);
        case String sized when sized.startsWith(SIZED) ->
            send(exchange, HttpURLConnection.HTTP_OK, spaces(sizeIn(sized)), true);
        case String chunked when chunked.startsWith(CHUNKED) ->
            send(exchange, HttpURLConnection.HTTP_OK, marked(sizeIn(chunked)), false);
        case String trailing when trailing.startsWith(TRAILING) -> sendTrailing(exchange, trailing);
        case String gzipped when gzipped.startsWith(GZIPPED) -> sendGzipped(exchange, gzipped);
        default ->
            send(
                exchange,
                HttpURLConnection.HTTP_OK,
                JSON_VALUE.getBytes(StandardCharsets.US_ASCII),
                true);
      }
    }
  }

  /** Returns the size at the end of a path. */
  private int sizeIn(String path) {
    return Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
  }

  /** Returns that many spaces. */
  private byte[] spaces(int size) {
    var bytes = new byte[size];
    Arrays.fill(bytes, (byte) ' ');
    return bytes;
  }

  /** Returns that many bytes, starting with {@link #MARKER}. */
  private byte[] marked(int size) {
    var bytes = spaces(size);
    var marker = MARKER.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(marker, 0, bytes, 0, Math.min(marker.length, size));
    return bytes;
  }

  /** Sends a whole body of bytes, with its length or chunked. */
  private void send(HttpExchange exchange, int status, byte[] body, boolean withLength)
      throws IOException {
    sendTyped(exchange, status, OCTETS, body, withLength);
  }

  /** Sends a whole body of the given type, with its length or chunked. */
  private void sendTyped(
      HttpExchange exchange, int status, String type, byte[] body, boolean withLength)
      throws IOException {
    exchange.getResponseHeaders().add(CONTENT_TYPE, type);
    exchange.sendResponseHeaders(status, withLength ? body.length : 0);
    exchange.getResponseBody().write(body);
  }

  /** Sends {@link #JSON_VALUE} and then the number of spaces at the end of the path. */
  private void sendTrailing(HttpExchange exchange, String path) throws IOException {
    var value = JSON_VALUE.getBytes(StandardCharsets.US_ASCII);
    var body = Arrays.copyOf(value, value.length + sizeIn(path));
    Arrays.fill(body, value.length, body.length, (byte) ' ');
    sendTyped(exchange, HttpURLConnection.HTTP_OK, JSON, body, true);
  }

  /** Sends the number of spaces at the end of the path, compressed with gzip. */
  private void sendGzipped(HttpExchange exchange, String path) throws IOException {
    exchange.getResponseHeaders().add("Content-Encoding", "gzip");
    exchange.getResponseHeaders().add(CONTENT_TYPE, JSON);
    exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
    try (var gzip = new GZIPOutputStream(exchange.getResponseBody())) {
      gzip.write(spaces(sizeIn(path)));
    }
  }

  /** Sends spaces until the client stops reading or the server closes. */
  private void sendEndless(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().add(CONTENT_TYPE, JSON);
    exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
    var chunk = spaces(CHUNK);
    try (OutputStream body = exchange.getResponseBody()) {
      // A loop, as the body ends only when a write fails.
      while (closed.getCount() > 0) {
        body.write(chunk);
      }
    }
  }

  /** Announces 50 GB, sends the headers only, then waits until the server closes. */
  private void stall(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().add(CONTENT_TYPE, JSON);
    exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, ANNOUNCED_LENGTH);
    try (var body = exchange.getResponseBody()) {
      body.flush();
      closed.await(STALL_FOR.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }
}
