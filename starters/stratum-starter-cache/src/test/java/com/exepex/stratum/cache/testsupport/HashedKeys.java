package com.exepex.stratum.cache.testsupport;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.experimental.UtilityClass;

/** Computes the hash a Redis cache key over its limit is stored under, for the tests to expect. */
@UtilityClass
public class HashedKeys {

  /**
   * Returns the SHA-256 hash of the key, in lower-case hex.
   *
   * @param key the key's bytes
   * @return the 64 hex digits
   */
  public static String sha256Hex(byte[] key) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key));
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException(missing);
    }
  }
}
