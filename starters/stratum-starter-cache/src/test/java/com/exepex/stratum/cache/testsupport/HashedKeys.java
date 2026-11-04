package com.exepex.stratum.cache.testsupport;

import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.BiConsumer;
import lombok.experimental.UtilityClass;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.data.redis.cache.RedisCacheWriter;

/**
 * Builds the Redis cache keys the size-limit tests write, and the hashed keys they expect a key
 * over its limit to be stored under.
 */
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

  /**
   * Returns the key as the writer stores it when it is over the limit.
   *
   * @param prefix the key's prefix, kept as it is
   * @param key the whole key, prefix included
   * @return the prefix, {@code sha256:} and the key's hash
   */
  public static byte[] hashed(String prefix, byte[] key) {
    return bytes(prefix + "sha256:" + sha256Hex(key));
  }

  /**
   * Returns a key of the given length in bytes that starts with the prefix.
   *
   * @param prefix the key's prefix
   * @param length the key's length in bytes, prefix included
   * @return the key
   */
  public static byte[] key(String prefix, int length) {
    return bytes(prefix + "k".repeat(length - prefix.length()));
  }

  /**
   * Returns the text's UTF-8 bytes.
   *
   * @param text the text
   * @return its bytes
   */
  public static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Returns a named writer call that takes a key, for a parameterized test.
   *
   * @param name the call's name, shown in the test report
   * @param call the call, given the writer and the key
   * @return the test's arguments
   */
  public static Arguments keyCall(String name, BiConsumer<RedisCacheWriter, byte[]> call) {
    return arguments(name, call);
  }
}
