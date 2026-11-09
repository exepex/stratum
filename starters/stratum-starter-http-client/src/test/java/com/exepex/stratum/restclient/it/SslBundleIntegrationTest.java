package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.restclient.testsupport.GeneratedKeyStores;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.TlsDownstream;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import com.exepex.stratum.restclient.testsupport.clients.PricingClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The inventory client calls the bank with the {@code bank} bundle; the pricing client has none.
 * Connections are short-lived, so that a reload shows on the next call.
 */
@SpringBootTest(
    classes = ClientsApplication.class,
    properties = {
      RestClientTestData.INVENTORY_SETTINGS + ".ssl-bundle=bank",
      RestClientTestData.INVENTORY_SETTINGS + ".connection-lifetime=100ms",
      "spring.ssl.bundle.watch.file.quiet-period=100ms"
    })
@Timeout(60)
@DisplayName("A client with ssl-bundle presents that bundle's certificate, and follows its reload")
class SslBundleIntegrationTest {

  /** The prefix of the bundle's settings. */
  private static final String BUNDLE = "spring.ssl.bundle.jks.bank";

  /** The first client certificate's subject. */
  private static final String FIRST_CLIENT = "bank-client";

  /** The renewed client certificate's subject. */
  private static final String RENEWED_CLIENT = "bank-client-renewed";

  /** The subject the bank sees for the first client certificate. */
  private static final String FIRST_SUBJECT = "CN=" + FIRST_CLIENT;

  /** The subject the bank sees for the renewed client certificate. */
  private static final String RENEWED_SUBJECT = "CN=" + RENEWED_CLIENT;

  /** The store of the client certificates the bank trusts. */
  private static final String BANK_TRUST = "bank-trust.p12";

  /** The common name of a bank key whose certificate holds only {@code bank.test}. */
  private static final String MISNAMED_BANK = "bank-misnamed";

  /** The pricing service, called without a bundle. */
  @AutoClose private static final ScriptedDownstream PRICING = ScriptedDownstream.start();

  /** The folder of the stores, made once. */
  @TempDir private static Path folder;

  /** The stores, made at test time. */
  private static GeneratedKeyStores certificates;

  /** The bank, which requires a client certificate it trusts. */
  private static TlsDownstream bank;

  /** A bank whose certificate does not hold the address the client calls. */
  private static TlsDownstream misnamedBank;

  @Autowired private InventoryClient inventoryClient;

  /**
   * Makes the servers' and both clients' keys, has the banks trust both client certificates and the
   * clients trust the banks, and starts the banks.
   *
   * @throws IOException if keytool fails
   * @throws GeneralSecurityException if a store is not valid
   */
  @BeforeAll
  static void startBanks() throws IOException, GeneralSecurityException {
    certificates = new GeneratedKeyStores(folder);
    certificates.newKey("bank.p12", "bank");
    certificates.newKey("misnamed-bank.p12", MISNAMED_BANK, "dns:bank.test");
    certificates.newKey("client.p12", FIRST_CLIENT);
    certificates.newKey("renewed.p12", RENEWED_CLIENT);
    certificates.trust("bank.p12", "bank", "client-trust.p12");
    certificates.trust("misnamed-bank.p12", MISNAMED_BANK, "client-trust.p12");
    certificates.trust("client.p12", FIRST_CLIENT, BANK_TRUST);
    certificates.trust("renewed.p12", RENEWED_CLIENT, BANK_TRUST);
    bank = new TlsDownstream(certificates, "bank.p12", BANK_TRUST);
    misnamedBank = new TlsDownstream(certificates, "misnamed-bank.p12", BANK_TRUST);
  }

  /** Stops the banks. */
  @AfterAll
  static void stopBanks() {
    bank.close();
    misnamedBank.close();
  }

  /**
   * Points the clients at the bank and at pricing, and sets the bundle from a copy of the first
   * client key.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void banksAndBundle(DynamicPropertyRegistry registry) {
    registry.add(RestClientTestData.INVENTORY_URL, () -> bank.url());
    registry.add(RestClientTestData.PRICING_URL, PRICING::url);
    registry.add(BUNDLE + ".keystore.location", () -> copyOfFirstKey("bank-bundle.p12"));
    registry.add(BUNDLE + ".keystore.password", () -> certificates.password());
    registry.add(BUNDLE + ".keystore.type", () -> "PKCS12");
    registry.add(
        BUNDLE + ".truststore.location", () -> certificates.store("client-trust.p12").toUri());
    registry.add(BUNDLE + ".truststore.password", () -> certificates.password());
    registry.add(BUNDLE + ".truststore.type", () -> "PKCS12");
    registry.add(BUNDLE + ".reload-on-update", () -> "true");
  }

  /** Copies the first client key to a bundle file of its own and returns the file's address. */
  private static String copyOfFirstKey(String bundleFile) {
    try {
      Files.copy(
          certificates.store("client.p12"),
          certificates.store(bundleFile),
          StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException copyFailed) {
      throw new IllegalStateException(copyFailed);
    }
    return certificates.store(bundleFile).toUri().toString();
  }

  @Test
  @DisplayName("the bank sees the bundle's client certificate")
  void sendsClientCertificate() {
    inventoryClient.stock();

    assertThat(bank.clientSubjects()).contains(FIRST_SUBJECT);
  }

  @Test
  @DisplayName("a client without a bundle is not changed by another client's bundle")
  void otherClientWithoutBundle(@Autowired PricingClient pricingClient) {
    assertThat(pricingClient.price()).isEqualTo(ScriptedDownstream.BODY);
  }

  @Nested
  @DisplayName("when the certificate file is renewed")
  class Reload {

    /**
     * Gives this application a bundle file of its own, which the test renews.
     *
     * @param registry the test's properties
     */
    @DynamicPropertySource
    static void ownBundleFile(DynamicPropertyRegistry registry) {
      registry.add(BUNDLE + ".keystore.location", () -> copyOfFirstKey("reloaded.p12"));
    }

    @Test
    @DisplayName("new connections present the new certificate")
    void reloadedCertificateUsed(@Autowired InventoryClient inventory) throws IOException {
      inventory.stock();
      Files.copy(
          certificates.store("renewed.p12"),
          certificates.store("reloaded.p12"),
          StandardCopyOption.REPLACE_EXISTING);

      await()
          .atMost(RestClientTestData.WAIT)
          .until(() -> RENEWED_SUBJECT.equals(lastSubjectAfterCall(inventory)));
      assertThat(bank.clientSubjects()).contains(FIRST_SUBJECT, RENEWED_SUBJECT);
    }

    /** Calls the bank once and returns the subject of the client certificate it saw last. */
    private String lastSubjectAfterCall(InventoryClient inventory) {
      inventory.stock();
      return bank.clientSubjects().getLast();
    }
  }

  @Nested
  @DisplayName("when the bank's certificate does not hold the address called")
  class OtherName {

    /**
     * Points the inventory client at the misnamed bank.
     *
     * @param registry the test's properties
     */
    @DynamicPropertySource
    static void misnamedAddress(DynamicPropertyRegistry registry) {
      registry.add(RestClientTestData.INVENTORY_URL, () -> misnamedBank.url());
    }

    @Test
    @DisplayName("the call fails the host check")
    void otherHostNameRefused(@Autowired InventoryClient inventory) {
      // The JDK's host check fails it with CertificateException, Apache's with
      // SSLPeerUnverifiedException; either one means the name was checked.
      assertThatThrownBy(inventory::stock)
          .rootCause()
          .isInstanceOfAny(CertificateException.class, SSLPeerUnverifiedException.class);
    }
  }
}
