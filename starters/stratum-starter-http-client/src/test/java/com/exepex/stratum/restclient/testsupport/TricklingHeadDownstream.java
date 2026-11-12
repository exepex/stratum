package com.exepex.stratum.restclient.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A downstream service on a plain socket, which writes the answer's status line and headers itself
 * and so can send them slowly, as a server's HTTP library never does. It keeps each connection open
 * for the next request and answers by path:
 *
 * <ul>
 *   <li>{@link #OK}: 200 with the body {@link #BODY}, at once;
 *   <li>{@link #TRICKLED_HEAD}: the same answer, one byte every {@link #GAP};
 *   <li>{@link #STALLED_HEAD}: the status line after {@link #LATE_STATUS}, then nothing for {@link
 *       #STALL_FOR};
 *   <li>{@link #JUNK_FIRST}: a line of junk before the same answer, which Apache's parser skips.
 * </ul>
 */
public final class TricklingHeadDownstream implements AutoCloseable {

  /** The path of an answer sent at once. */
  public static final String OK = "/ok";

  /** The path of an answer whose head comes one byte at a time. */
  public static final String TRICKLED_HEAD = "/trickled-head";

  /** The path of an answer whose status line comes late, after which it stops. */
  public static final String STALLED_HEAD = "/stalled-head";

  /** The path of an answer with a line of junk before its status line. */
  public static final String JUNK_FIRST = "/junk-first";

  /** The body of every answer. */
  public static final String BODY = "ok";

  /** The wait before each byte of a {@link #TRICKLED_HEAD} answer: 2s for its 40 bytes. */
  public static final Duration GAP = Duration.ofMillis(50);

  /** The answer, head and body. */
  private static final String ANSWER =
      "HTTP/1.1 200 OK\r\nContent-Length: " + BODY.length() + "\r\n\r\n" + BODY;

  /** The status line a {@link #STALLED_HEAD} answer sends before it stops. */
  private static final String STATUS_LINE = "HTTP/1.1 200 OK\r\n";

  /** The wait before a {@link #STALLED_HEAD} answer's status line. */
  public static final Duration LATE_STATUS = Duration.ofMillis(800);

  /** How long a {@link #STALLED_HEAD} answer stops, far above any timeout a test sets. */
  private static final Duration STALL_FOR = Duration.ofSeconds(10);

  /** The end of a request's head. */
  private static final String HEAD_END = "\r\n\r\n";

  /** The server's socket. */
  private final ServerSocket server;

  /** Opened by {@link #close()}, so that a paused answer ends at once. */
  private final CountDownLatch closed = new CountDownLatch(1);

  /** The connections open now, closed with the server. */
  private final Set<Socket> connections = ConcurrentHashMap.newKeySet();

  /** How many connections the clients opened. */
  private final AtomicInteger openedCount = new AtomicInteger();

  /**
   * Starts the server on a free port of the loopback address.
   *
   * @return the running server
   */
  public static TricklingHeadDownstream start() {
    try {
      return new TricklingHeadDownstream(new ServerSocket(0, 0, InetAddress.getLoopbackAddress()));
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  /**
   * Takes connections on the server's socket, each served on a virtual thread of its own.
   *
   * @param server the server's socket
   */
  private TricklingHeadDownstream(ServerSocket server) {
    this.server = server;
    Thread.ofVirtual().start(this::acceptNext);
  }

  /**
   * Returns the server's address.
   *
   * @return the address, such as {@code http://127.0.0.1:41234}
   */
  public String url() {
    return "http://127.0.0.1:" + server.getLocalPort();
  }

  /**
   * Returns how many connections the clients have opened so far.
   *
   * @return the count
   */
  public int openedConnections() {
    return openedCount.get();
  }

  /** Closes the server and every connection, ending any answer still being sent. */
  @Override
  public void close() throws IOException {
    closed.countDown();
    server.close();
    for (var connection : connections) {
      try (connection) {
        connections.remove(connection);
      }
    }
  }

  /**
   * Takes the next connection, hands the one after it to a new thread, and answers each request on
   * it until the client or the server closes it.
   */
  private void acceptNext() {
    try (var connection = server.accept();
        var in = connection.getInputStream();
        var out = connection.getOutputStream()) {
      Thread.ofVirtual().start(this::acceptNext);
      openedCount.incrementAndGet();
      connections.add(connection);
      while (true) {
        respond(nextPath(in), out);
      }
    } catch (IOException | InterruptedException _) {
      // The server closed, or the client dropped the connection, as a cut call does.
      connections.removeIf(Socket::isClosed);
    }
  }

  /** Reads one request's head and returns its path, failing once the client is gone. */
  private String nextPath(InputStream in) throws IOException {
    var head = new ByteArrayOutputStream();
    while (!head.toString(StandardCharsets.US_ASCII).endsWith(HEAD_END)) {
      var next = in.read();
      if (next < 0) {
        throw new EOFException("The client closed the connection");
      }
      head.write(next);
    }
    return head.toString(StandardCharsets.US_ASCII).split(" ")[1];
  }

  /** Writes the answer to the request for a path. */
  private void respond(String path, OutputStream out) throws IOException, InterruptedException {
    switch (path) {
      case TRICKLED_HEAD -> trickle(ANSWER, out);
      case STALLED_HEAD -> {
        closed.await(LATE_STATUS.toMillis(), TimeUnit.MILLISECONDS);
        write(STATUS_LINE, out);
        closed.await(STALL_FOR.toMillis(), TimeUnit.MILLISECONDS);
      }
      case JUNK_FIRST -> write("junk\r\n" + ANSWER, out);
      default -> write(ANSWER, out);
    }
  }

  /** Writes text one byte every {@link #GAP}, stopping when the server closes. */
  private void trickle(String text, OutputStream out) throws IOException, InterruptedException {
    for (var oneByte : text.getBytes(StandardCharsets.US_ASCII)) {
      if (closed.await(GAP.toMillis(), TimeUnit.MILLISECONDS)) {
        return;
      }
      out.write(oneByte);
      out.flush();
    }
  }

  /** Writes text at once. */
  private void write(String text, OutputStream out) throws IOException {
    out.write(text.getBytes(StandardCharsets.US_ASCII));
    out.flush();
  }
}
