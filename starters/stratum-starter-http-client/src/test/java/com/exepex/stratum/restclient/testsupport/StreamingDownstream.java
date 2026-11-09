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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * A downstream service that sends its answers slowly and reads uploads as they arrive, for the
 * streaming tests. It answers by path:
 *
 * <ul>
 *   <li>{@link #SLOW}: 200 with {@link #SLOW_BODY}, one byte every {@link #GAP};
 *   <li>{@link #STALL}: 200 with one byte, then nothing for {@link #STALL_FOR};
 *   <li>{@link #UPLOAD}: reads the request body and remembers how it came, in {@link
 *       #lastUpload()}.
 * </ul>
 */
public final class StreamingDownstream implements AutoCloseable {

  /** The path of an answer sent one byte at a time. */
  public static final String SLOW = "/slow";

  /** The path of an answer that stops after its first byte. */
  public static final String STALL = "/stall";

  /** The path that takes uploads. */
  public static final String UPLOAD = "/upload";

  /** The body of a {@link #SLOW} answer. */
  public static final String SLOW_BODY = "xxxxxxxx";

  /** The wait before each byte of a {@link #SLOW} answer. */
  public static final Duration GAP = Duration.ofMillis(200);

  /** How long a {@link #STALL} answer stops, far above any timeout a test sets. */
  private static final Duration STALL_FOR = Duration.ofSeconds(10);

  /** The server. */
  private final HttpServer server;

  /** Opened by {@link #close()}, so that a paused answer ends at once. */
  private final CountDownLatch closed = new CountDownLatch(1);

  /** How the last upload came. */
  private final AtomicReference<@Nullable Upload> uploads = new AtomicReference<>();

  /** Opens the server on the loopback address, with a virtual thread per exchange. */
  private StreamingDownstream() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(SLOW, this::sendSlowly);
    server.createContext(STALL, this::stopAfterFirstByte);
    server.createContext(UPLOAD, this::takeUpload);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  /**
   * Starts a server.
   *
   * @return the server
   */
  public static StreamingDownstream start() {
    try {
      return new StreamingDownstream();
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
    return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
  }

  /**
   * Returns how the last upload came, or {@code null} before the first.
   *
   * @return the last upload
   */
  public @Nullable Upload lastUpload() {
    return uploads.get();
  }

  @Override
  public void close() {
    closed.countDown();
    server.stop(0);
  }

  /** Sends {@link #SLOW_BODY} one byte at a time, {@link #GAP} apart. */
  private void sendSlowly(HttpExchange exchange) throws IOException {
    exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
    try (var body = exchange.getResponseBody()) {
      for (var oneByte : SLOW_BODY.getBytes(StandardCharsets.US_ASCII)) {
        pause(GAP);
        sendByte(body, oneByte);
      }
    }
  }

  /** Sends one byte and then stops for {@link #STALL_FOR}. */
  private void stopAfterFirstByte(HttpExchange exchange) throws IOException {
    exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
    try (var body = exchange.getResponseBody()) {
      sendByte(body, SLOW_BODY.charAt(0));
      pause(STALL_FOR);
    }
  }

  /** Reads an upload to the end and answers 200 with {@code ok}. */
  private void takeUpload(HttpExchange exchange) throws IOException {
    var headers = exchange.getRequestHeaders();
    long size = exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
    uploads.set(
        new Upload(
            headers.getFirst("Transfer-Encoding"), headers.getFirst("Content-Length"), size));
    var answer = "ok".getBytes(StandardCharsets.US_ASCII);
    exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, answer.length);
    try (var body = exchange.getResponseBody()) {
      body.write(answer);
    }
  }

  /** Writes one byte and flushes it, so that it leaves at once. */
  private void sendByte(OutputStream body, int oneByte) throws IOException {
    body.write(oneByte);
    body.flush();
  }

  /** Waits the given time, or until the server closes, keeping the thread's interrupt. */
  private void pause(Duration time) {
    try {
      closed.await(time.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * How an upload came.
   *
   * @param transferEncoding the {@code Transfer-Encoding} header, {@code chunked} for a streamed
   *     body
   * @param contentLength the {@code Content-Length} header, set when the client buffered the body
   * @param size the bytes received
   */
  public record Upload(
      @Nullable String transferEncoding, @Nullable String contentLength, long size) {}
}
