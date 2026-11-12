package com.exepex.stratum.restclient.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.http.HttpStatus;

/**
 * A downstream service on HTTPS that requires a client certificate (mutual TLS). It answers every
 * request with 200 and {@link ScriptedDownstream#BODY}, and keeps the common name of each client
 * certificate it saw.
 */
public final class TlsDownstream implements AutoCloseable {

  /** The HTTPS server. */
  private final HttpsServer server;

  /** The subject of each request's client certificate, in order. */
  private final List<String> seenClientSubjects = new CopyOnWriteArrayList<>();

  /**
   * Starts the server on 127.0.0.1 with a key store and the trust store it checks clients with.
   *
   * @param certificates the test's stores
   * @param keyStoreName the server's key store
   * @param trustStoreName the store of the client certificates it accepts
   * @throws IOException if a store cannot be read or the server cannot start
   * @throws GeneralSecurityException if a store is not valid
   */
  public TlsDownstream(GeneratedKeyStores certificates, String keyStoreName, String trustStoreName)
      throws IOException, GeneralSecurityException {
    var sslContext = SSLContext.getInstance("TLS");
    var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(load(certificates, keyStoreName), certificates.password().toCharArray());
    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(load(certificates, trustStoreName));
    sslContext.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
    server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setHttpsConfigurator(new ClientCertificateRequired(sslContext));
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/", this::answer);
    server.start();
  }

  /**
   * Returns the server's address, such as {@code https://127.0.0.1:43211}.
   *
   * @return the address
   */
  public String url() {
    return "https://127.0.0.1:" + server.getAddress().getPort();
  }

  /**
   * Returns the subject of the client certificate of each request, in order, such as {@code
   * CN=bank-client}.
   *
   * @return the subjects
   */
  public List<String> clientSubjects() {
    return List.copyOf(seenClientSubjects);
  }

  /** Stops the server at once. */
  @Override
  public void close() {
    server.stop(0);
  }

  /** Keeps the client certificate's subject and answers 200. */
  private void answer(HttpExchange exchange) throws IOException {
    try (exchange) {
      var session = ((HttpsExchange) exchange).getSSLSession();
      seenClientSubjects.add(
          ((X509Certificate) session.getPeerCertificates()[0]).getSubjectX500Principal().getName());
      var body = ScriptedDownstream.BODY.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(HttpStatus.OK.value(), body.length);
      exchange.getResponseBody().write(body);
    }
  }

  /** Reads one of the test's PKCS12 stores. */
  private KeyStore load(GeneratedKeyStores certificates, String name)
      throws IOException, GeneralSecurityException {
    var store = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(certificates.store(name))) {
      store.load(in, certificates.password().toCharArray());
    }
    return store;
  }

  /** Requires a client certificate on every connection. */
  private static final class ClientCertificateRequired extends HttpsConfigurator {

    /**
     * Uses the server's TLS set-up.
     *
     * @param sslContext the server's keys and trust
     */
    ClientCertificateRequired(SSLContext sslContext) {
      super(sslContext);
    }

    /**
     * Asks every client for its certificate and refuses one without.
     *
     * @param parameters the connection's TLS parameters
     */
    @Override
    public void configure(HttpsParameters parameters) {
      var sslParameters = getSSLContext().getDefaultSSLParameters();
      sslParameters.setNeedClientAuth(true);
      parameters.setSSLParameters(sslParameters);
    }
  }
}
