package com.exepex.stratum.restclient.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * A downstream service that records each request it gets, its header lines and body as they came,
 * for the tests of headers and interceptors. Every path answers 200 with {@link #BODY}, except
 * {@link #FLAKY}, whose odd requests answer 503, so that each call of it is retried once.
 */
public final class EchoDownstream implements AutoCloseable {

  /** The path most calls use. */
  public static final String ECHO = "/echo";

  /** The path whose first, third and later odd requests answer 503. */
  public static final String FLAKY = "/flaky";

  /** The body of every 200 answer. */
  public static final String BODY = "echoed";

  /** The server. */
  private final HttpServer server;

  /** Every request, in order. */
  private final List<Received> received = new CopyOnWriteArrayList<>();

  /** The size of each read of a request's body. */
  private static final int CHUNK_SIZE = 8192;

  /** Opened when the first bytes of a request's body arrive. */
  private final AtomicReference<CountDownLatch> bodyBytes =
      new AtomicReference<>(new CountDownLatch(1));

  /** The requests of {@link #FLAKY} so far. */
  private final AtomicInteger flakyCount = new AtomicInteger();

  /** Opens the server on the loopback address, with a virtual thread per exchange. */
  private EchoDownstream() throws IOException {
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
  public static EchoDownstream start() {
    try {
      return new EchoDownstream();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  /**
   * Returns the address to set as a client's {@code url}.
   *
   * @return the address
   */
  public String url() {
    var address = server.getAddress();
    return "http://" + address.getAddress().getHostAddress() + ":" + address.getPort();
  }

  /**
   * Returns how many requests the server got so far.
   *
   * @return the count
   */
  public int requestCount() {
    return received.size();
  }

  /**
   * Returns the last request the server got.
   *
   * @return the last request
   * @throws java.util.NoSuchElementException before the first request
   */
  public Received last() {
    return received.getLast();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  /**
   * Waits until a request's body starts to arrive, before the client has written all of it when the
   * body streams.
   *
   * @param limit the longest wait
   * @return whether body bytes arrived within the limit
   * @throws InterruptedException if the wait is interrupted
   */
  public boolean awaitBodyBytes(Duration limit) throws InterruptedException {
    return bodyBytes.get().await(limit.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** Forgets that body bytes arrived, so that the next {@link #awaitBodyBytes} waits again. */
  public void expectBodyBytes() {
    bodyBytes.set(new CountDownLatch(1));
  }

  /** Reads a request's body as it arrives, telling {@link #awaitBodyBytes} at its first bytes. */
  private byte[] bodyOf(InputStream body) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var chunk = new byte[CHUNK_SIZE];
    // A loop, not readAllBytes: each part is noted as it arrives, before the rest is written.
    for (var read = body.read(chunk); read >= 0; read = body.read(chunk)) {
      bytes.write(chunk, 0, read);
      bodyBytes.get().countDown();
    }
    return bytes.toByteArray();
  }

  /** Records a request and answers it. */
  private void answer(HttpExchange exchange) throws IOException {
    try (exchange) {
      var headers = exchange.getRequestHeaders();
      received.add(
          Received.builder()
              .path(exchange.getRequestURI().getPath())
              .headers(Map.copyOf(headers))
              .body(bodyOf(exchange.getRequestBody()))
              .transferEncoding(headers.getFirst("Transfer-Encoding"))
              .build());
      var fails =
          FLAKY.equals(exchange.getRequestURI().getPath()) && flakyCount.incrementAndGet() % 2 == 1;
      var answer = BODY.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "text/plain");
      exchange.sendResponseHeaders(
          fails ? HttpURLConnection.HTTP_UNAVAILABLE : HttpURLConnection.HTTP_OK, answer.length);
      exchange.getResponseBody().write(answer);
    }
  }

  /**
   * One request as the server got it.
   *
   * @param path the request's path
   * @param headers its header lines by name, each value one line in the order sent; names as the
   *     JDK's server spells them, such as {@code X-tenant}
   * @param body its body
   * @param transferEncoding its {@code Transfer-Encoding}, {@code chunked} for a streamed body
   */
  @Builder
  public record Received(
      String path,
      Map<String, List<String>> headers,
      byte[] body,
      @Nullable String transferEncoding) {

    /**
     * Returns every line of one header, in the order sent.
     *
     * @param name the header's name, compared without case
     * @return its values, empty when it was not sent
     */
    public List<String> values(String name) {
      return headers.entrySet().stream()
          .filter(header -> header.getKey().equalsIgnoreCase(name))
          .flatMap(header -> header.getValue().stream())
          .toList();
    }

    /**
     * Returns the body as text.
     *
     * @return the body
     */
    public String text() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }
}
