package com.exepex.stratum.restclient.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * A downstream service that sends its answers slowly and reads uploads as they arrive, for the
 * streaming tests. It answers by path:
 *
 * <ul>
 *   <li>{@link #SLOW}: 200 with {@link #SLOW_BODY}, one byte every {@link #GAP};
 *   <li>{@link #STALL}: 200 with one byte, then nothing for {@link #STALL_FOR};
 *   <li>{@link #TRICKLE}: 200 with {@link #TRICKLE_BYTES} bytes, one every {@link #GAP};
 *   <li>{@link #LATE_STALL}: 200 with {@link #LATE_STALL_BYTES} bytes, one every {@link #GAP}, then
 *       nothing for {@link #STALL_FOR};
 *   <li>{@link #UPLOAD}: reads the request body and remembers how it came, in {@link
 *       #lastUpload()}.
 * </ul>
 */
public final class StreamingDownstream implements AutoCloseable {

  /** The path of an answer sent one byte at a time. */
  public static final String SLOW = "/slow";

  /** The path of an answer that stops after its first byte. */
  public static final String STALL = "/stall";

  /** The path of a long answer sent one byte at a time, 4s in all. */
  public static final String TRICKLE = "/trickle";

  /** The path of an answer that stops after a few bytes. */
  public static final String LATE_STALL = "/late-stall";

  /** The path that takes uploads. */
  public static final String UPLOAD = "/upload";

  /** The body of a {@link #SLOW} answer. */
  public static final String SLOW_BODY = "xxxxxxxx";

  /** The wait before each byte of a {@link #SLOW} answer. */
  public static final Duration GAP = Duration.ofMillis(200);

  /** The bytes of a {@link #TRICKLE} answer. */
  public static final int TRICKLE_BYTES = 20;

  /** The bytes a {@link #LATE_STALL} answer sends before it stops. */
  private static final int LATE_STALL_BYTES = 4;

  /** How long a {@link #STALL} answer stops, far above any timeout a test sets. */
  private static final Duration STALL_FOR = Duration.ofSeconds(10);

  /** The server. */
  private final HttpServer server;

  /** Opened by {@link #close()}, so that a paused answer ends at once. */
  private final CountDownLatch closed = new CountDownLatch(1);

  /** How the last upload came. */
  private final AtomicReference<@Nullable Upload> uploads = new AtomicReference<>();

  /** The bytes of every upload received so far, counted as they arrive. */
  private final AtomicLong receivedByteCount = new AtomicLong();

  /** Opens the server on the loopback address, with a virtual thread per exchange. */
  private StreamingDownstream() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(SLOW, exchange -> sendSlowly(exchange, SLOW_BODY.length(), Duration.ZERO));
    server.createContext(STALL, this::stopAfterFirstByte);
    server.createContext(TRICKLE, exchange -> sendSlowly(exchange, TRICKLE_BYTES, Duration.ZERO));
    server.createContext(LATE_STALL, exchange -> sendSlowly(exchange, LATE_STALL_BYTES, STALL_FOR));
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
    var address = server.getAddress();
    try {
      return new URI(
              "http",
              null,
              address.getAddress().getHostAddress(),
              address.getPort(),
              null,
              null,
              null)
          .toString();
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Returns how the last upload came, or {@code null} before the first.
   *
   * @return the last upload
   */
  public @Nullable Upload lastUpload() {
    return uploads.get();
  }

  /**
   * Returns the bytes of every upload received so far, counted as they arrive, so a test sees an
   * upload reach the server while it is still being written.
   *
   * @return the bytes received
   */
  public long receivedBytes() {
    return receivedByteCount.get();
  }

  @Override
  public void close() {
    closed.countDown();
    server.stop(0);
  }

  /**
   * Sends the given number of bytes of {@link #SLOW_BODY}'s kind, each after {@link #GAP}, then
   * stops for the given time.
   */
  private void sendSlowly(HttpExchange exchange, int byteCount, Duration stopFor)
      throws IOException {
    exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
    try (var body = exchange.getResponseBody()) {
      // A loop: each byte waits for the gap before it, until the server closes.
      for (var sent = 0; sent < byteCount && pause(GAP); sent++) {
        sendByte(body, SLOW_BODY.charAt(0));
      }
      pause(stopFor);
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
    long size = exchange.getRequestBody().transferTo(new CountingSink());
    uploads.set(
        Upload.builder()
            .transferEncoding(headers.getFirst("Transfer-Encoding"))
            .contentLength(headers.getFirst("Content-Length"))
            .size(size)
            .build());
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

  /**
   * Waits the given time, or until the server closes, keeping the thread's interrupt.
   *
   * @return whether the whole time passed with the server open, so that the answer goes on
   */
  private boolean pause(Duration time) {
    try {
      return !closed.await(time.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Drops what it is given, and counts it as received. */
  private final class CountingSink extends OutputStream {

    @Override
    public void write(int oneByte) {
      receivedByteCount.incrementAndGet();
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
      receivedByteCount.addAndGet(length);
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
  @Builder
  public record Upload(
      @Nullable String transferEncoding, @Nullable String contentLength, long size) {}
}
