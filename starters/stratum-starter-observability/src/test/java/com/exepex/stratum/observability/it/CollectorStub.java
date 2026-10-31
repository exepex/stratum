package com.exepex.stratum.observability.it;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Stands in for an OpenTelemetry Collector and for a service the application calls: accepts every
 * request on a free local port and keeps each request's path, body and trace headers.
 */
class CollectorStub implements AutoCloseable {

  /** The OTLP path for metrics. */
  static final String METRICS_PATH = "/v1/metrics";

  /** The OTLP path for spans. */
  static final String TRACES_PATH = "/v1/traces";

  /** The W3C header that carries the trace ID and the caller's span ID. */
  static final String TRACEPARENT_HEADER = "traceparent";

  /** The W3C header that carries baggage, such as the correlation ID. */
  static final String BAGGAGE_HEADER = "baggage";

  /** Each received request, as its path followed by its body read as Latin-1 text. */
  private final List<String> requests = new CopyOnWriteArrayList<>();

  /** The W3C {@code traceparent} header each path last received. */
  private final Map<String, String> traceparentHeaderByPath = new ConcurrentHashMap<>();

  /** The W3C {@code baggage} header each path last received. */
  private final Map<String, String> baggageHeaderByPath = new ConcurrentHashMap<>();

  /** The server that receives the requests. */
  private final HttpServer server;

  /**
   * Starts the stub on a free port.
   *
   * @throws UncheckedIOException if no local port is free
   */
  CollectorStub() {
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException noFreePort) {
      throw new UncheckedIOException(noFreePort);
    }
    server.createContext("/", this::receive);
    server.start();
  }

  /**
   * Returns the full address of an OTLP path on this stub.
   *
   * @param path the OTLP path, such as {@link #METRICS_PATH}
   * @return the address
   */
  String url(String path) {
    return "http://localhost:" + server.getAddress().getPort() + path;
  }

  /**
   * Returns the application's arguments, preceded by the ones that send every metric and span to
   * this stub.
   *
   * @param applicationArgs the application's other arguments
   * @return all the arguments
   */
  String[] withExportsSentHere(String... applicationArgs) {
    var exportArgs =
        List.of(
            "--management.otlp.metrics.export.url=" + url(METRICS_PATH),
            "--management.opentelemetry.tracing.export.otlp.endpoint=" + url(TRACES_PATH),
            "--management.tracing.sampling.probability=1.0");
    return Stream.concat(exportArgs.stream(), Stream.of(applicationArgs)).toArray(String[]::new);
  }

  /**
   * Returns the bodies received on a path; OTLP's protobuf keeps strings readable.
   *
   * @param path the OTLP path, such as {@link #METRICS_PATH}
   * @return the bodies, oldest first
   */
  List<String> bodiesOf(String path) {
    return requests.stream()
        .filter(request -> request.startsWith(path + " "))
        .map(request -> request.substring(path.length() + 1))
        .toList();
  }

  /**
   * Returns the {@code traceparent} header the path last received.
   *
   * @param path the path, such as {@code /downstream/rest-client}
   * @return the header, or empty if the path received none
   */
  Optional<String> traceparentHeaderReceivedOn(String path) {
    return Optional.ofNullable(traceparentHeaderByPath.get(path));
  }

  /**
   * Returns the {@code baggage} header the path last received.
   *
   * @param path the path, such as {@code /downstream/rest-client}
   * @return the header, or empty if the path received none
   */
  Optional<String> baggageHeaderReceivedOn(String path) {
    return Optional.ofNullable(baggageHeaderByPath.get(path));
  }

  /** Keeps the request and answers with an empty success. */
  private void receive(HttpExchange exchange) throws IOException {
    try (exchange) {
      var path = exchange.getRequestURI().getPath();
      var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
      requests.add(path + " " + body);
      Optional.ofNullable(exchange.getRequestHeaders().getFirst(TRACEPARENT_HEADER))
          .ifPresent(traceparent -> traceparentHeaderByPath.put(path, traceparent));
      Optional.ofNullable(exchange.getRequestHeaders().getFirst(BAGGAGE_HEADER))
          .ifPresent(baggage -> baggageHeaderByPath.put(path, baggage));
      exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, -1);
    }
  }

  /** Stops the stub. */
  @Override
  public void close() {
    server.stop(0);
  }
}
