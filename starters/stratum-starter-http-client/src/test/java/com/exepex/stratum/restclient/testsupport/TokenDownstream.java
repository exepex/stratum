package com.exepex.stratum.restclient.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.springframework.http.HttpHeaders;

/**
 * An identity provider and the service it protects, on one test server. {@link #TOKEN} answers a
 * client-credentials request with a new token each time, {@code service-1}, {@code service-2} and
 * so on, or as {@link #tokenAnswer(TokenAnswer)} sets. {@link ScriptedDownstream#STOCK} answers
 * {@link ScriptedDownstream#BODY} to a token that is not refused, else 401, and records each {@code
 * Authorization} it gets.
 */
public final class TokenDownstream implements AutoCloseable {

  /** The path of the token endpoint. */
  public static final String TOKEN = "/token";

  /** The start of each token the endpoint issues. */
  public static final String TOKEN_PREFIX = "service-";

  /** The type of a token answer. */
  private static final String JSON = "application/json";

  /** The type of the protected service's answer. */
  private static final String TEXT = "text/plain";

  /** How long the server holds a request it was told to hold, far above any test's timeout. */
  private static final long HOLD_SECONDS = 10;

  /** How the token endpoint answers. */
  public enum TokenAnswer {
    /** A new token, valid for 5 minutes. */
    ISSUE,
    /** 401 with {@code invalid_client}, as after a secret rotation. */
    REFUSE,
    /** No answer until the server closes. */
    HOLD
  }

  /** The server. */
  private final HttpServer server;

  /** Opened by {@link #close()}, so that a held request ends at once. */
  private final CountDownLatch closed = new CountDownLatch(1);

  /** How the token endpoint answers now. */
  private final AtomicReference<TokenAnswer> answerMode = new AtomicReference<>(TokenAnswer.ISSUE);

  /** The token requests the endpoint got. */
  private final AtomicInteger tokenRequestCount = new AtomicInteger();

  /** The tokens the protected service refuses. */
  private final Set<String> refusedTokens = ConcurrentHashMap.newKeySet();

  /** Whether the protected service refuses every token. */
  private final AtomicBoolean refusesAll = new AtomicBoolean();

  /** The {@code Authorization} of each call of the protected service, in order. */
  private final List<String> authorizationsSent = new CopyOnWriteArrayList<>();

  /** Starts the server on a free port of the loopback address. */
  public TokenDownstream() {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException failed) {
      throw new UncheckedIOException(failed);
    }
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(TOKEN, this::answerToken);
    server.createContext(ScriptedDownstream.STOCK, this::answerProtected);
    server.start();
  }

  /**
   * Returns the server's address.
   *
   * @return the address, such as {@code http://127.0.0.1:41234}
   */
  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /**
   * Sets how the token endpoint answers from now on.
   *
   * @param answer the answer
   */
  public void tokenAnswer(TokenAnswer answer) {
    answerMode.set(answer);
  }

  /**
   * Returns the number of token requests so far.
   *
   * @return the number
   */
  public int tokenRequests() {
    return tokenRequestCount.get();
  }

  /** Has the protected service refuse every token issued so far, as after a key rotation. */
  public void refuseIssuedTokens() {
    IntStream.rangeClosed(1, tokenRequestCount.get())
        .mapToObj(number -> TOKEN_PREFIX + number)
        .forEach(refusedTokens::add);
  }

  /** Has the protected service refuse every token, issued or not. */
  public void refuseAllTokens() {
    refusesAll.set(true);
  }

  /**
   * Returns the {@code Authorization} of each call of the protected service, empty when none.
   *
   * @return the values, in order
   */
  public List<String> authorizations() {
    return List.copyOf(authorizationsSent);
  }

  /** Stops the server, ending every held request. */
  @Override
  public void close() {
    closed.countDown();
    server.stop(0);
  }

  /** Answers a token request as set. */
  private void answerToken(HttpExchange exchange) throws IOException {
    try (exchange) {
      var number = tokenRequestCount.incrementAndGet();
      switch (answerMode.get()) {
        case ISSUE ->
            send(
                exchange,
                HttpURLConnection.HTTP_OK,
                JSON,
                "{\"access_token\":\""
                    + TOKEN_PREFIX
                    + number
                    + "\",\"token_type\":\"Bearer\",\"expires_in\":300}");
        case REFUSE ->
            send(
                exchange,
                HttpURLConnection.HTTP_UNAUTHORIZED,
                JSON,
                "{\"error\":\"invalid_client\"}");
        default -> hold(exchange);
      }
    }
  }

  /** Answers the protected service: the body to an accepted token, else 401. */
  private void answerProtected(HttpExchange exchange) throws IOException {
    try (exchange) {
      var authorization = exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION);
      authorizationsSent.add(authorization == null ? "" : authorization);
      var token = authorization == null ? "" : authorization.replace("Bearer ", "");
      var accepted =
          token.startsWith(TOKEN_PREFIX) && !refusesAll.get() && !refusedTokens.contains(token);
      if (accepted) {
        send(exchange, HttpURLConnection.HTTP_OK, TEXT, ScriptedDownstream.BODY);
      } else {
        send(exchange, HttpURLConnection.HTTP_UNAUTHORIZED, TEXT, "");
      }
    }
  }

  /** Holds a request until the server closes. */
  private void hold(HttpExchange exchange) {
    try {
      closed.await(HOLD_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
    exchange.close();
  }

  /** Sends an answer with a body of the type given. */
  private void send(HttpExchange exchange, int status, String contentType, String body)
      throws IOException {
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, contentType);
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      exchange.getResponseBody().write(bytes);
    }
  }
}
