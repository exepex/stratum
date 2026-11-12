package com.exepex.stratum.restclient.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Key stores made at test time with the JDK's {@code keytool}, so that no private key or password
 * is kept in the repository: a server certificate for 127.0.0.1, client certificates, and the trust
 * stores each side checks the other with. Every store is PKCS12 with one random password.
 */
public final class GeneratedKeyStores {

  /** The number of random bytes in the password. */
  private static final int PASSWORD_BYTES = 16;

  /** The longest keytool may take for one step. */
  private static final long KEYTOOL_LIMIT_SECONDS = 60;

  /** The folder that holds the stores. */
  private final Path folder;

  /** The password of every store, random for each test run. */
  private final String storePassword;

  /**
   * Prepares stores in a folder, with a new random password.
   *
   * @param folder the folder, such as a test's temporary one
   */
  public GeneratedKeyStores(Path folder) {
    this.folder = folder;
    var bytes = new byte[PASSWORD_BYTES];
    new SecureRandom().nextBytes(bytes);
    this.storePassword = HexFormat.of().formatHex(bytes);
  }

  /**
   * Returns the password of every store.
   *
   * @return the password
   */
  public String password() {
    return storePassword;
  }

  /**
   * Returns the path of a store in the folder.
   *
   * @param name the store's file name, such as {@code client.p12}
   * @return the path
   */
  public Path store(String name) {
    return folder.resolve(name);
  }

  /**
   * Makes a store holding a new key and its self-signed certificate for 127.0.0.1.
   *
   * @param storeName the store's file name
   * @param commonName the certificate's common name, such as {@code bank-client}
   * @throws IOException if keytool fails
   */
  public void newKey(String storeName, String commonName) throws IOException {
    newKey(storeName, commonName, "ip:127.0.0.1");
  }

  /**
   * Makes a store holding a new key and its self-signed certificate for the given names.
   *
   * @param storeName the store's file name
   * @param commonName the certificate's common name, such as {@code bank}
   * @param names the names the certificate holds, in keytool's form, such as {@code dns:bank.test}
   * @throws IOException if keytool fails
   */
  public void newKey(String storeName, String commonName, String names) throws IOException {
    keytool(
        "-genkeypair",
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-validity",
        "2",
        "-alias",
        commonName,
        "-dname",
        "CN=" + commonName,
        "-ext",
        "SAN=" + names,
        "-keystore",
        store(storeName).toString(),
        "-storetype",
        "PKCS12",
        "-storepass",
        storePassword,
        "-keypass",
        storePassword);
  }

  /**
   * Adds the certificate of one store's key to a trust store, made if it does not exist.
   *
   * @param keyStoreName the store that holds the key
   * @param commonName the key's common name, its alias
   * @param trustStoreName the trust store's file name
   * @throws IOException if keytool fails
   */
  public void trust(String keyStoreName, String commonName, String trustStoreName)
      throws IOException {
    var certificate = store(commonName + ".crt").toString();
    keytool(
        "-exportcert",
        "-alias",
        commonName,
        "-file",
        certificate,
        "-keystore",
        store(keyStoreName).toString(),
        "-storepass",
        storePassword);
    keytool(
        "-importcert",
        "-noprompt",
        "-alias",
        commonName,
        "-file",
        certificate,
        "-keystore",
        store(trustStoreName).toString(),
        "-storetype",
        "PKCS12",
        "-storepass",
        storePassword);
  }

  /** Runs keytool from the JDK that runs the tests, with the given arguments. */
  private void keytool(String... arguments) throws IOException {
    var command =
        new ArrayList<>(
            List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
    command.addAll(List.of(arguments));
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    try {
      var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(KEYTOOL_LIMIT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
        throw new IOException("keytool failed: " + output);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException(interrupted);
    }
  }
}
