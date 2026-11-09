package com.exepex.stratum.restclient.testsupport;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

/**
 * A downstream service on a plain socket, so that tests see each connection: how many were opened,
 * how many are still open, and every request's path. It answers by path:
 *
 * <ul>
 *   <li>{@link #STOCK}: 200 with {@link #BODY};
 *   <li>{@link #HELD}: 200 once {@link #release()} is next called;
 *   <li>{@link #UNAVAILABLE}: 503;
 *   <li>{@link #MOVED}: 302 to {@link #STOCK};
 *   <li>{@link #GZIPPED}: 200 with {@link #BODY} compressed with gzip;
 *   <li>{@link #SHORT_KEEP_ALIVE}: 200 with {@code Keep-Alive: timeout=1};
 *   <li>{@link #STATUS} and a status, such as {@code /status/502}: that status;
 *   <li>{@link #RETRY_AFTER} and a value, such as {@code /retry-after/1}: 503 with that {@code
 *       Retry-After};
 *   <li>{@link #DROPPED}: no answer; the connection is closed.
 * </ul>
 *
 * <p>A request's body is read and dropped.
 */
public final class ScriptedDownstream implements AutoCloseable {

  /** The path of a plain answer. */
  public static final String STOCK = "/stock";

  /** The path of an answer that waits for {@link #release()}. */
  public static final String HELD = "/held";

  /** The path of a 503 answer. */
  public static final String UNAVAILABLE = "/unavailable";

  /** The path of a redirect to {@link #STOCK}. */
  public static final String MOVED = "/moved";

  /** The path of a gzip answer. */
  public static final String GZIPPED = "/gzipped";

  /** The path of an answer that keeps its connection alive for 1 second only. */
  public static final String SHORT_KEEP_ALIVE = "/short-keep-alive";

  /** The start of the paths that answer with the status that follows, such as 502. */
  public static final String STATUS = "/status/";

  /** The start of the paths that answer 503 with the {@code Retry-After} that follows. */
  public static final String RETRY_AFTER = "/retry-after/";

  /** The path whose connection is closed without an answer. */
  public static final String DROPPED = "/dropped";

  /** A path that answers with the status given as {@code {status}}, for a client interface. */
  public static final String ANY_STATUS = STATUS + "{status}";

  /** The body of every 200 answer. */
  public static final String BODY = "in stock";

  /** The end of each line of an answer's head. */
  private static final String CRLF = "\r\n";

  /** The status line of every answer that succeeds. */
  private static final String OK = "200 OK";

  /** The start of a request's {@code Content-Length} header, in lowercase. */
  private static final String CONTENT_LENGTH = "content-length:";

  /** How long a held answer waits at most, so a failed test does not hang. */
  private static final Duration HOLD_LIMIT = Duration.ofSeconds(10);

  /** The listening socket. */
  private final ServerSocket serverSocket;

  /** How long a connection may stay unused before this server closes it. */
  private final Duration closeIdleAfter;

  /** The connections opened so far. */
  private final AtomicInteger connectionCount = new AtomicInteger();

  /** The connections still open. */
  private final AtomicInteger openConnectionCount = new AtomicInteger();

  /** The connections this server closed after they sat unused for {@code closeIdleAfter}. */
  private final AtomicInteger idleClosedCount = new AtomicInteger();

  /** The connections the client reset, such as one it dropped after its timeout. */
  private final AtomicInteger resetCount = new AtomicInteger();

  /** The requests that wait at {@link #HELD}. */
  private final AtomicInteger heldRequestCount = new AtomicInteger();

  /** The path of every request, in order. */
  private final List<String> receivedPaths = new CopyOnWriteArrayList<>();

  /** The header lines of every request, in order, such as {@code X-Api-Key: geo}. */
  private final List<List<String>> receivedHeaders = new CopyOnWriteArrayList<>();

  /** The latch the held requests wait on, opened and replaced by {@link #release()}. */
  private final AtomicReference<CountDownLatch> releaseLatch =
      new AtomicReference<>(new CountDownLatch(1));

  /** Opens the server on the loopback address and starts accepting connections. */
  private ScriptedDownstream(Duration closeIdleAfter) throws IOException {
    this.closeIdleAfter = closeIdleAfter;
    this.serverSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
    Thread.ofVirtual().start(this::accept);
  }

  /**
   * Starts a server that keeps every connection open until the client closes it.
   *
   * @return the server
   */
  public static ScriptedDownstream start() {
    return startClosingIdleAfter(Duration.ofMinutes(1));
  }

  /**
   * Starts a server that closes a connection left unused for the given time, as a load balancer
   * does.
   *
   * @param closeIdleAfter how long a connection may stay unused
   * @return the server
   */
  public static ScriptedDownstream startClosingIdleAfter(Duration closeIdleAfter) {
    try {
      return new ScriptedDownstream(closeIdleAfter);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Returns the server's address, such as {@code http://127.0.0.1:43211}.
   *
   * @return the address
   */
  public String url() {
    try {
      return new URI(
              "http",
              null,
              serverSocket.getInetAddress().getHostAddress(),
              serverSocket.getLocalPort(),
              null,
              null,
              null)
          .toString();
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Returns the number of connections opened so far.
   *
   * @return the number of connections
   */
  public int connections() {
    return connectionCount.get();
  }

  /**
   * Returns the number of connections still open.
   *
   * @return the number of open connections
   */
  public int openConnections() {
    return openConnectionCount.get();
  }

  /**
   * Returns the number of connections this server closed because they sat unused.
   *
   * @return the number of connections closed for being idle
   */
  public int idleConnectionsClosed() {
    return idleClosedCount.get();
  }

  /**
   * Returns the number of connections the client reset.
   *
   * @return the number of reset connections
   */
  public int resetConnections() {
    return resetCount.get();
  }

  /**
   * Returns the number of requests that wait at {@link #HELD}.
   *
   * @return the number of held requests
   */
  public int heldRequests() {
    return heldRequestCount.get();
  }

  /**
   * Returns the path of every request, in order.
   *
   * @return the paths
   */
  public List<String> paths() {
    return List.copyOf(receivedPaths);
  }

  /**
   * Returns the value of one header in each request, in order, or an empty text where a request did
   * not send it. The name is matched without regard to case.
   *
   * @param name the header's name, such as Authorization
   * @return the values
   */
  public List<String> headerValues(String name) {
    var prefix = name.toLowerCase(Locale.ROOT) + ":";
    return receivedHeaders.stream().map(lines -> valueIn(lines, prefix)).toList();
  }

  /** Returns the value of the first header line that starts with the prefix, or an empty text. */
  private String valueIn(List<String> lines, String prefix) {
    return lines.stream()
        .filter(line -> line.toLowerCase(Locale.ROOT).startsWith(prefix))
        .map(line -> line.substring(prefix.length()).strip())
        .findFirst()
        .orElse("");
  }

  /** Lets every request held now answer; later ones are held again. */
  public void release() {
    releaseLatch.getAndSet(new CountDownLatch(1)).countDown();
  }

  /** Stops accepting connections and lets held requests answer. */
  @Override
  public void close() throws IOException {
    release();
    serverSocket.close();
  }

  /** Accepts connections until the server closes. */
  private void accept() {
    while (!serverSocket.isClosed()) {
      try {
        serveLater(serverSocket.accept());
      } catch (IOException _) {
        return;
      }
    }
  }

  /** Counts a new connection and answers it on a thread of its own. */
  private void serveLater(Socket connection) {
    connectionCount.incrementAndGet();
    openConnectionCount.incrementAndGet();
    Thread.ofVirtual().start(() -> serve(connection));
  }

  /** Answers the requests of one connection until either side closes it. */
  private void serve(Socket connection) {
    try (connection;
        var in =
            new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
        var out = connection.getOutputStream()) {
      connection.setSoTimeout(Math.toIntExact(closeIdleAfter.toMillis()));
      var requestLine = in.readLine();
      while (requestLine != null && answered(requestLine, in, out)) {
        requestLine = in.readLine();
      }
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    } catch (SocketTimeoutException _) {
      idleClosedCount.incrementAndGet();
    } catch (IOException _) {
      resetCount.incrementAndGet();
    } finally {
      openConnectionCount.decrementAndGet();
    }
  }

  /** Reads one request and answers it; returns false when the connection is to be closed. */
  private boolean answered(String requestLine, BufferedReader in, OutputStream out)
      throws IOException, InterruptedException {
    var headers = readHeaders(in);
    receivedHeaders.add(headers);
    skipBody(in, bodyLength(headers));
    var path = requestLine.split(" ")[1];
    receivedPaths.add(path);
    if (DROPPED.equals(path)) {
      return false;
    }
    answer(path, out);
    return true;
  }

  /** Reads the request's header lines. */
  private List<String> readHeaders(BufferedReader in) throws IOException {
    var lines = new ArrayList<String>();
    var header = in.readLine();
    while (header != null && !header.isEmpty()) {
      lines.add(header);
      header = in.readLine();
    }
    return lines;
  }

  /** Returns the length of the request's body, from its {@code Content-Length}, or 0. */
  private long bodyLength(List<String> headers) {
    return headers.stream()
        .filter(header -> header.toLowerCase(Locale.ROOT).startsWith(CONTENT_LENGTH))
        .mapToLong(header -> Long.parseLong(header.substring(CONTENT_LENGTH.length()).strip()))
        .findFirst()
        .orElse(0L);
  }

  /** Reads and drops a request's body; the tests send only ASCII bodies. */
  private void skipBody(BufferedReader in, long bodyLength) throws IOException {
    var skipped = in.skip(bodyLength);
    if (skipped != bodyLength) {
      throw new IOException("The request's body ended early");
    }
  }

  /** Writes the answer for one path. */
  private void answer(String path, OutputStream out) throws IOException, InterruptedException {
    var body = BODY.getBytes(StandardCharsets.UTF_8);
    switch (path) {
      case HELD -> {
        var latch = releaseLatch.get();
        heldRequestCount.incrementAndGet();
        latch.await(HOLD_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
        write(out, OK, "", body);
      }
      case UNAVAILABLE -> write(out, "503 Service Unavailable", "", new byte[0]);
      case MOVED -> write(out, "302 Found", "Location: " + STOCK + CRLF, new byte[0]);
      case GZIPPED -> write(out, OK, "Content-Encoding: gzip" + CRLF, gzip(body));
      case SHORT_KEEP_ALIVE -> write(out, OK, "Keep-Alive: timeout=1" + CRLF, body);
      case String status when status.startsWith(STATUS) ->
          write(out, status.substring(STATUS.length()) + " Scripted", "", new byte[0]);
      case String wait when wait.startsWith(RETRY_AFTER) ->
          write(
              out,
              "503 Service Unavailable",
              "Retry-After: " + wait.substring(RETRY_AFTER.length()) + CRLF,
              new byte[0]);
      default -> write(out, OK, "", body);
    }
  }

  /** Writes one answer that keeps the connection open. */
  private void write(OutputStream out, String status, String headers, byte[] body)
      throws IOException {
    var head =
        "HTTP/1.1 "
            + status
            + CRLF
            + "Content-Type: text/plain"
            + CRLF
            + "Content-Length: "
            + body.length
            + CRLF
            + headers
            + CRLF;
    out.write(head.getBytes(StandardCharsets.US_ASCII));
    out.write(body);
    out.flush();
  }

  /** Returns the bytes compressed with gzip. */
  private byte[] gzip(byte[] body) throws IOException {
    var compressed = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(compressed)) {
      gzip.write(body);
    }
    return compressed.toByteArray();
  }
}
