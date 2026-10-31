package com.exepex.stratum.test.it;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.StarterTestSupport;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StarterTestSupport's calls to a running service")
class StarterTestSupportIntegrationTest {

  @Test
  @DisplayName("getFromService sends a GET request with the headers and returns the body")
  void sendsRequestWithHeaders() throws IOException, InterruptedException {
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/echo",
        exchange -> {
          var body = exchange.getRequestHeaders().getFirst("X-Order").getBytes(UTF_8);
          exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var response =
          StarterTestSupport.getFromService(
              server.getAddress().getPort(), "/echo", "X-Order", "42");

      assertThat(response.body()).isEqualTo("42");
    } finally {
      server.stop(0);
    }
  }
}
