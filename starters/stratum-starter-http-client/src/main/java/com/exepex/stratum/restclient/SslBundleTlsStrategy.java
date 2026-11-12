package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSocket;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.HttpsSupport;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.reactor.ssl.SSLBufferMode;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;

/**
 * Opens a client's TLS connections with the certificate and trust material of one {@code
 * spring.ssl.bundle}, for mutual TLS. When Spring Boot reloads the bundle, such as after a
 * certificate is renewed, new connections use the new one; connections already open end within the
 * connection lifetime. The other service's certificate and host name are always checked.
 */
@StratumInternal
public final class SslBundleTlsStrategy implements TlsSocketStrategy {

  /** The strategy built from the bundle's current certificate. */
  private final AtomicReference<TlsSocketStrategy> current;

  /**
   * Builds the strategy from the bundle and follows its reloads.
   *
   * @param sslBundles Spring Boot's SSL bundles
   * @param bundleName the bundle's name, as in {@code spring.ssl.bundle.*.<name>}
   * @throws org.springframework.boot.ssl.NoSuchSslBundleException if no bundle has that name
   */
  public SslBundleTlsStrategy(SslBundles sslBundles, String bundleName) {
    current = new AtomicReference<>(strategyOf(sslBundles.getBundle(bundleName)));
    sslBundles.addBundleUpdateHandler(bundleName, reloaded -> current.set(strategyOf(reloaded)));
  }

  /**
   * Starts TLS on a new connection with the bundle's current certificate.
   *
   * @param socket the connected socket
   * @param target the other service's host name
   * @param port the other service's port
   * @param attachment Apache's attachment, passed on
   * @param context the call's context, passed on
   * @return the TLS socket
   * @throws IOException if the handshake fails
   */
  @Override
  public SSLSocket upgrade(
      Socket socket, String target, int port, @Nullable Object attachment, HttpContext context)
      throws IOException {
    return current.get().upgrade(socket, target, port, attachment, context);
  }

  /** Builds Apache's strategy from a bundle, with its protocols and ciphers if it sets any. */
  private TlsSocketStrategy strategyOf(SslBundle bundle) {
    var options = bundle.getOptions();
    return new DefaultClientTlsStrategy(
        bundle.createSslContext(),
        options.getEnabledProtocols(),
        options.getCiphers(),
        SSLBufferMode.STATIC,
        HttpsSupport.getDefaultHostnameVerifier());
  }
}
