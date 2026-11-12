package com.exepex.stratum.restclient.testsupport.errors;

import com.exepex.stratum.httpclient.HttpClientConstants;
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
import java.util.concurrent.atomic.AtomicInteger;
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
 *   <li>{@link #ENDLESS_ERROR}: 500, a body that never ends;
 *   <li>{@link #STALLED_ERROR}: 500, a {@code Content-Length} of 50 GB, 64 KB, then nothing until
 *       the server closes;
 *   <li>{@link #LARGE_ANSWER}: 200, {@link #LARGE_SIZE} bytes;
 *   <li>{@link #CUT_JSON}: 200, the start of a JSON body, then nothing until the server closes;
 *   <li>{@link #BAD_JSON}: 200, typed as JSON but not JSON;
 *   <li>{@link #CUT_ORDER}: 200, an order that stops inside its list of lines, then nothing until
 *       the server closes;
 *   <li>{@link #SHORT_JSON}: 200, a whole JSON body that ends right after a list starts;
 *   <li>{@link #BAD_PROTOBUF}: 200, typed as protobuf but not protobuf;
 *   <li>{@link #CUT_PROTOBUF}: 200, the start of a protobuf body, then nothing until the server
 *       closes.
 * </ul>
 *
 * <p>The requests to the last five are counted in {@link #bodyRequests()}.
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

  /** The path of an error body that never ends. */
  public static final String ENDLESS_ERROR = "/endless-error";

  /** The path of an error body that promises 50 GB and stops after 64 KB. */
  public static final String STALLED_ERROR = "/stalled-error";

  /** The path of a large body that succeeds. */
  public static final String LARGE_ANSWER = "/large-answer";

  /** The path of a JSON answer whose body stops after its first bytes. */
  public static final String CUT_JSON = "/cut-json";

  /** The path of a 200 whose JSON body is not valid. */
  public static final String BAD_JSON = "/bad-json";

  /** The path of an order whose JSON body stops inside its list of lines. */
  public static final String CUT_ORDER = "/cut-order";

  /** The path of a 200 whose whole JSON body ends right after its list of lines starts. */
  public static final String SHORT_JSON = "/short-json";

  /** The path of a 200 whose protobuf body is not valid. */
  public static final String BAD_PROTOBUF = "/bad-protobuf";

  /** The path of a protobuf answer whose body stops after its first bytes. */
  public static final String CUT_PROTOBUF = "/cut-protobuf";

  /** The code in the {@link #PROBLEM} body. */
  public static final String DOWNSTREAM_CODE = "ORD-0001";

  /** The size of each large body, 1 MB, far over the 64 KB read of an error body. */
  public static final int LARGE_SIZE = 1024 * 1024;

  /** The length a cut-off answer promises, far more than it sends. */
  private static final int CUT_LENGTH = 1000;

  /** The length a {@link #STALLED_ERROR} answer promises: 50 GB. */
  private static final long STALLED_LENGTH = 50L * 1024 * 1024 * 1024;

  /** Bytes that are not protobuf: a field's number whose varint never ends. */
  private static final byte[] NOT_PROTOBUF = {-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1};

  /** The start of a protobuf message: a field of 5 bytes, of which none follows. */
  private static final byte[] PROTOBUF_START = {0x0a, 0x05};

  /** The size of each write of an endless body. */
  private static final int CHUNK = 8192;

  /** The name of the header that gives a body's type. */
  private static final String CONTENT_TYPE = "Content-Type";

  /** The content type of a JSON body. */
  private static final String JSON = "application/json";

  /** The content type of a protobuf body. */
  private static final String PROTOBUF = "application/x-protobuf";

  /** The content type of a text body. */
  private static final String TEXT = "text/plain";

  /** The problem detail's content type. */
  private static final String PROBLEM_JSON = "application/problem+json";

  /** The server. */
  private final HttpServer server;

  /** Opened by {@link #close()}, so that a {@link #CUT_JSON} answer ends at once. */
  private final CountDownLatch closed = new CountDownLatch(1);

  /** How many requests the paths of bad and cut-off bodies had. */
  private final AtomicInteger bodyCount = new AtomicInteger();

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
    server.createContext(ENDLESS_ERROR, this::sendEndlessError);
    server.createContext(STALLED_ERROR, this::sendStalledError);
    server.createContext(CUT_JSON, exchange -> sendCut(exchange, JSON, bytes("{\"id\":\"o-1\",")));
    server.createContext(
        BAD_JSON, exchange -> sendBad(exchange, JSON, bytes(JsonReadFailures.BAD_JSON)));
    server.createContext(
        CUT_ORDER, exchange -> sendCut(exchange, JSON, bytes(JsonReadFailures.ORDER_START)));
    server.createContext(
        SHORT_JSON, exchange -> sendBad(exchange, JSON, bytes(JsonReadFailures.ORDER_OPEN_LIST)));
    server.createContext(BAD_PROTOBUF, exchange -> sendBad(exchange, PROTOBUF, NOT_PROTOBUF));
    server.createContext(CUT_PROTOBUF, exchange -> sendCut(exchange, PROTOBUF, PROTOBUF_START));
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

  /**
   * Returns how many requests the paths of bad and cut-off bodies, such as {@link #BAD_JSON} and
   * {@link #CUT_ORDER}, had so far.
   *
   * @return the count
   */
  public int bodyRequests() {
    return bodyCount.get();
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
    exchange.getResponseHeaders().add(CONTENT_TYPE, contentType);
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

  /** Sends a 500 whose body never ends, until the client stops reading or the server closes. */
  private void sendEndlessError(HttpExchange exchange) throws IOException {
    clientPorts.add(exchange.getRemoteAddress().getPort());
    exchange.getResponseHeaders().add(CONTENT_TYPE, TEXT);
    exchange.sendResponseHeaders(HttpURLConnection.HTTP_INTERNAL_ERROR, 0);
    var chunk = new byte[CHUNK];
    Arrays.fill(chunk, (byte) 'x');
    try (var out = exchange.getResponseBody()) {
      // A loop, as the body ends only when a write fails.
      while (closed.getCount() > 0) {
        out.write(chunk);
      }
    }
  }

  /** Sends a 500 that promises 50 GB, sends 64 KB and then nothing until the server closes. */
  private void sendStalledError(HttpExchange exchange) throws IOException {
    clientPorts.add(exchange.getRemoteAddress().getPort());
    exchange.getResponseHeaders().add(CONTENT_TYPE, TEXT);
    exchange.sendResponseHeaders(HttpURLConnection.HTTP_INTERNAL_ERROR, STALLED_LENGTH);
    try (var out = exchange.getResponseBody()) {
      out.write(new byte[HttpClientConstants.ERROR_BODY_LIMIT]);
      out.flush();
      awaitClose();
    }
  }

  /** Counts the request and sends a 200 whose whole body is not valid for its type. */
  private void sendBad(HttpExchange exchange, String contentType, byte[] body) throws IOException {
    bodyCount.incrementAndGet();
    send(exchange, HttpURLConnection.HTTP_OK, contentType, body);
  }

  /**
   * Counts the request and sends a 200 that promises a body, sends the given start of it and then
   * nothing more until the server closes, far longer than the client's timeout.
   */
  private void sendCut(HttpExchange exchange, String contentType, byte[] start) throws IOException {
    bodyCount.incrementAndGet();
    exchange.getResponseHeaders().add(CONTENT_TYPE, contentType);
    exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, CUT_LENGTH);
    try (var out = exchange.getResponseBody()) {
      out.write(start);
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
