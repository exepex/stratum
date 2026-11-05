package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.io.InputStream;
import java.nio.ByteBuffer;
import tools.jackson.databind.util.ByteBufferBackedInputStream;

/**
 * A Redis cache value as it was stored: plain JSON with no type name in it. Stratum's cache layer
 * reads it as the cached method's return type; a read that skips the layer, such as a direct {@code
 * CacheManager} call, gets this holder.
 */
@StratumInternal
public final class CachedJson {

  /** The stored bytes, read-only. */
  private final ByteBuffer json;

  /**
   * Holds stored bytes without copying them.
   *
   * @param stored the bytes as Redis returned them
   */
  public CachedJson(ByteBuffer stored) {
    this.json = stored.asReadOnlyBuffer();
  }

  /**
   * Returns how many bytes were stored.
   *
   * @return the size in bytes
   */
  public int size() {
    return json.remaining();
  }

  /**
   * Opens the stored bytes for reading, from the start, each time.
   *
   * @return a stream over the bytes
   */
  public InputStream open() {
    return new ByteBufferBackedInputStream(json.duplicate());
  }
}
