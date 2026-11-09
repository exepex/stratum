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
import java.util.List;
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
 *   <li>{@link #SHORT_KEEP_ALIVE}: 200 with {@code Keep-Alive: timeout=1}.
 * </ul>
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

  /** The body of every 200 answer. */
  public static final String BODY = "in stock";

  /** The status line of every answer that succeeds. */
  private static final String OK = "200 OK";

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
      for (var requestLine = in.readLine(); requestLine != null; requestLine = in.readLine()) {
        skipHeaders(in);
        var path = requestLine.split(" ")[1];
        receivedPaths.add(path);
        answer(path, out);
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

  /** Reads the request's headers; requests in these tests have no body. */
  private void skipHeaders(BufferedReader in) throws IOException {
    var header = in.readLine();
    while (header != null && !header.isEmpty()) {
      header = in.readLine();
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
      case MOVED -> write(out, "302 Found", "Location: " + STOCK + "\r\n", new byte[0]);
      case GZIPPED -> write(out, OK, "Content-Encoding: gzip\r\n", gzip(body));
      case SHORT_KEEP_ALIVE -> write(out, OK, "Keep-Alive: timeout=1\r\n", body);
      default -> write(out, OK, "", body);
    }
  }

  /** Writes one answer that keeps the connection open. */
  private void write(OutputStream out, String status, String headers, byte[] body)
      throws IOException {
    var head =
        "HTTP/1.1 "
            + status
            + "\r\nContent-Type: text/plain\r\nContent-Length: "
            + body.length
            + "\r\n"
            + headers
            + "\r\n";
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
