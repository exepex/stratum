package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.testsupport.EchoDownstream;
import com.exepex.stratum.restclient.testsupport.headers.HeadersApplication;
import com.exepex.stratum.restclient.testsupport.headers.HeadersTestData;
import com.exepex.stratum.restclient.testsupport.headers.PlainClient;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.filter.RequestContextFilter;

/**
 * A request parsed by a real servlet container, Jetty, and bound by Spring's {@link
 * RequestContextFilter} as a service's web stack does, whose handler calls a Stratum client that
 * copies {@code X-Tenant}.
 */
@SpringBootTest(
    classes = HeadersApplication.class,
    properties = HttpClientConstants.DEFAULTS + ".copy-headers=" + HeadersTestData.TENANT)
@DisplayName("A client copies the headers of a request a real servlet container read, whole")
class CopiedHeadersServletContainerIntegrationTest {

  /** The path the container answers by calling the client. */
  private static final String CALL = "/call";

  /** The longest head of a request Jetty reads by default: request line and headers. */
  private static final int HEAD_LIMIT = 8192;

  /** A value that leaves room under {@link #HEAD_LIMIT} for the rest of the request's head. */
  private static final String NEAR_LIMIT_VALUE = "a".repeat(HEAD_LIMIT - 200);

  /** The status line of the container's answer once the client's call is done. */
  private static final String CALLED = "HTTP/1.1 204 No Content";

  /** The server the client calls. */
  @AutoClose private static final EchoDownstream DOWNSTREAM = EchoDownstream.start();

  @Autowired private PlainClient plain;

  /** The servlet container the test's requests go to. */
  private Server container;

  /**
   * Points the clients at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    HeadersTestData.pointClientsAt(registry, DOWNSTREAM::url);
  }

  /** Starts the container, with Spring's filter and a servlet that calls the client. */
  @BeforeEach
  void startContainer() throws Exception {
    var context = new ServletContextHandler();
    context.addFilter(RequestContextFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
    context.addServlet(new ServletHolder(new CallingServlet(plain)), CALL);
    container = new Server(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    container.setHandler(context);
    container.start();
  }

  /** Stops the container. */
  @AfterEach
  void stopContainer() throws Exception {
    container.stop();
  }

  @Test
  @DisplayName("a header goes on the call as the container read it")
  void headerCopied() throws IOException {
    assertThat(sendToContainer(HeadersTestData.TENANT + ": acme")).isEqualTo(CALLED);

    assertThat(tenantSent()).containsExactly("acme");
  }

  @Test
  @DisplayName("a header sent on two lines goes on the call with both values, in order")
  void repeatedHeaderCopied() throws IOException {
    assertThat(
            sendToContainer(HeadersTestData.TENANT + ": acme", HeadersTestData.TENANT + ": globex"))
        .isEqualTo(CALLED);

    assertThat(tenantSent()).containsExactly("acme", "globex");
  }

  @Test
  @DisplayName(
      "a value just under the container's limit on a request's head goes on the call whole")
  void nearLimitValueCopied() throws IOException {
    assertThat(sendToContainer(HeadersTestData.TENANT + ": " + NEAR_LIMIT_VALUE)).isEqualTo(CALLED);

    assertThat(tenantSent()).containsExactly(NEAR_LIMIT_VALUE);
  }

  /** Returns the copied header's lines in the last call the server got. */
  private List<String> tenantSent() {
    return DOWNSTREAM.last().values(HeadersTestData.TENANT);
  }

  /**
   * Sends the container a request with the given header lines, as written on the wire, and returns
   * its answer's status line.
   */
  private String sendToContainer(String... headerLines) throws IOException {
    var port = ((ServerConnector) container.getConnectors()[0]).getLocalPort();
    var head =
        "GET "
            + CALL
            + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
            + String.join("\r\n", headerLines)
            + "\r\n\r\n";
    try (var socket = new Socket(InetAddress.getLoopbackAddress(), port);
        var answer =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
      socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
      return answer.readLine();
    }
  }

  /** A service's handler: it calls the client while the container's request is bound. */
  @RequiredArgsConstructor
  private static final class CallingServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    /** The client the handler calls. */
    private final transient PlainClient client;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
      client.echo();
      response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
  }
}
