package com.exepex.stratum.cache.testsupport;

import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import java.nio.charset.StandardCharsets;
import lombok.experimental.UtilityClass;

/**
 * Builds the Lettuce commands the timeout and span tests read keys from, as Spring Data sends them.
 */
@UtilityClass
public class RedisCommandTestData {

  /**
   * Returns a GET of the key.
   *
   * @param key the key, as text
   * @return the command
   */
  public static Command<byte[], byte[], String> get(String key) {
    return new Command<>(
        CommandType.GET,
        new StatusOutput<>(ByteArrayCodec.INSTANCE),
        new CommandArgs<>(ByteArrayCodec.INSTANCE).addKey(key.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Returns a PING, a command without a key.
   *
   * @return the command
   */
  public static Command<byte[], byte[], String> ping() {
    return new Command<>(
        CommandType.PING,
        new StatusOutput<>(ByteArrayCodec.INSTANCE),
        new CommandArgs<>(ByteArrayCodec.INSTANCE));
  }
}
