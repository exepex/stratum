package com.exepex.stratum.observability.it;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for an OpenTelemetry Collector: accepts every OTLP request on a free local port and
 * keeps each request's path and body.
 */
class CollectorStub implements AutoCloseable {

  /** The OTLP path for metrics. */
  static final String METRICS_PATH = "/v1/metrics";

  /** The OTLP path for spans. */
  static final String TRACES_PATH = "/v1/traces";

  /** Each received request, as its path followed by its body read as Latin-1 text. */
  private final List<String> requests = new CopyOnWriteArrayList<>();

  /** The server that receives the requests. */
  private final HttpServer server;

  /** Starts the stub on a free port. */
  CollectorStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
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

  /** Keeps the request and answers with an empty success. */
  private void receive(HttpExchange exchange) throws IOException {
    try (exchange) {
      var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
      requests.add(exchange.getRequestURI().getPath() + " " + body);
      exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, -1);
    }
  }

  /** Stops the stub. */
  @Override
  public void close() {
    server.stop(0);
  }
}
