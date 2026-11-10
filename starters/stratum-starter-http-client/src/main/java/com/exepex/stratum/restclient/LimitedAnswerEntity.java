package com.exepex.stratum.restclient;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.HttpEntityWrapper;
import org.jspecify.annotations.Nullable;

/**
 * An answer's body of which at most a limit of bytes is read: an error body ends there, and a
 * successful one fails with {@link ResponseBodyTooLarge}. One count, shared by every stream of the
 * body, holds the bytes the caller read and those drained when the body is closed. The drain stops
 * once the count passes the limit, or at once when the {@code Content-Length} is over it; Apache's
 * stream is then left unread, so the connection is dropped instead of reused, and the close does
 * not fail.
 */
final class LimitedAnswerEntity extends HttpEntityWrapper {

  /** The size of the buffer the drain on close reads into. */
  private static final int DRAIN_BUFFER = 8192;

  /** The most bytes of the body that are read. */
  private final long limit;

  /** The client whose successful answer fails past the limit, or null for an error body. */
  private final @Nullable String client;

  /** The bytes read so far, by the caller and by the drain. */
  private long count;

  /** Whether the body is known to pass the limit, which leaves Apache's stream unread. */
  private boolean limitPassed;

  /** Whether a read reached the end of the body. */
  private boolean ended;

  /** Wraps the body. */
  private LimitedAnswerEntity(HttpEntity entity, long limit, @Nullable String client) {
    super(entity);
    this.limit = limit;
    this.client = client;
  }

  /**
   * Returns an error body that ends after the limit.
   *
   * @param entity the error answer's body
   * @param limit the most bytes read
   * @return the limited body
   */
  static LimitedAnswerEntity endingAt(HttpEntity entity, long limit) {
    return new LimitedAnswerEntity(entity, limit, null);
  }

  /**
   * Returns a successful answer's body whose read fails once it passes the limit.
   *
   * @param entity the answer's body
   * @param limit the client's {@code response-body-limit}, in bytes
   * @param client the client's name, for the failure's message
   * @return the limited body
   */
  static LimitedAnswerEntity failingPast(HttpEntity entity, long limit, String client) {
    return new LimitedAnswerEntity(entity, limit, client);
  }

  /**
   * Returns the body, counted against the limit.
   *
   * @return the body
   * @throws IOException when it cannot be read
   */
  @Override
  public InputStream getContent() throws IOException {
    return new CountedStream(super.getContent());
  }

  /**
   * Returns the length of an error body when it is within the limit, or unknown; that of a
   * successful one as sent.
   *
   * @return the length, or -1
   */
  @Override
  public long getContentLength() {
    var length = super.getContentLength();
    return client == null && length > limit ? -1 : length;
  }

  /**
   * Writes the body through the count.
   *
   * @param out where the body is written
   * @throws IOException when it cannot be read or written, or a successful body passes the limit
   */
  @Override
  public void writeTo(OutputStream out) throws IOException {
    try (var content = getContent()) {
      content.transferTo(out);
    }
  }

  /**
   * Closes Apache's body, which drains it, unless it is known to pass the limit.
   *
   * @throws IOException when it cannot be closed
   */
  @Override
  public void close() throws IOException {
    if (!limitPassed) {
      super.close();
    }
  }

  /** Returns how many bytes may still be read within the limit. */
  private long left() {
    return Math.max(0, limit - count);
  }

  /** Returns whether a read reached the end of the body, or every byte its length gives. */
  private boolean isEnded() {
    var length = super.getContentLength();
    return ended || length >= 0 && count >= length;
  }

  /** Returns whether the body's {@code Content-Length} is over the limit. */
  private boolean lengthOverLimit() {
    return super.getContentLength() > limit;
  }

  /** Returns the failure of a successful body that passes the limit. */
  private ResponseBodyTooLarge tooLarge() {
    limitPassed = true;
    return new ResponseBodyTooLarge(Objects.requireNonNull(client), limit);
  }

  /** A stream of the body that counts every byte read against the limit. */
  private final class CountedStream extends FilterInputStream {

    /**
     * Wraps Apache's stream.
     *
     * @param content Apache's stream of the body
     */
    private CountedStream(InputStream content) {
      super(content);
    }

    /**
     * Reads one byte through the count.
     *
     * @return the byte, or -1 at the end
     * @throws IOException when it cannot be read, or a successful body passes the limit
     */
    @Override
    public int read() throws IOException {
      var oneByte = new byte[1];
      return read(oneByte, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(oneByte[0]);
    }

    /**
     * Reads bytes through the count: an error body ends at the limit, and a successful one fails on
     * the read that passes it, or on the first read when its {@code Content-Length} is over it.
     *
     * @param buffer where the bytes go
     * @param offset where in the buffer
     * @param length how many at most
     * @return how many were read, or -1 at the end
     * @throws IOException when it cannot be read, or a successful body passes the limit
     */
    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (client == null) {
        return left() <= 0 || limitPassed
            ? -1
            : counted(in.read(buffer, offset, (int) Math.min(length, left())));
      }
      failIfLimitPassed();
      var read = counted(in.read(buffer, offset, length));
      failIfOverLimit();
      return read;
    }

    /**
     * Skips bytes through the count, never past an error body's limit.
     *
     * @param skipped how many at most
     * @return how many were skipped
     * @throws IOException when it cannot be read, or a successful body passes the limit
     */
    @Override
    public long skip(long skipped) throws IOException {
      if (client == null) {
        var done = in.skip(Math.min(skipped, left()));
        count += done;
        return done;
      }
      failIfLimitPassed();
      var done = in.skip(skipped);
      count += done;
      failIfOverLimit();
      return done;
    }

    /**
     * Returns how many bytes can be read without blocking, never past an error body's limit.
     *
     * @return the count
     * @throws IOException when it cannot be read
     */
    @Override
    public int available() throws IOException {
      return client == null ? (int) Math.min(super.available(), left()) : super.available();
    }

    /**
     * Drains the rest of the body through the count, then closes Apache's stream, so that the
     * connection is used again, but reads no more than the limit in all: a body whose {@code
     * Content-Length} is over the limit, or whose end is not reached within it, is left unread, so
     * its connection is dropped, and the close does not fail. A failed read, such as a read
     * timeout, also leaves it unread and is thrown; Spring ignores a failure to close an answer.
     *
     * @throws IOException when the drain cannot read the body
     */
    @Override
    public void close() throws IOException {
      if (limitPassed || lengthOverLimit()) {
        limitPassed = true;
        return;
      }
      var buffer = new byte[DRAIN_BUFFER];
      try {
        // A loop, as each read may end the drain early; none reads past the limit.
        while (!isEnded() && left() > 0) {
          counted(in.read(buffer, 0, (int) Math.min(buffer.length, left())));
        }
      } catch (IOException failure) {
        limitPassed = true;
        throw failure;
      }
      if (isEnded()) {
        in.close();
      } else {
        limitPassed = true;
      }
    }

    /** Fails a read of a successful body known to pass the limit, by its count or its length. */
    private void failIfLimitPassed() throws ResponseBodyTooLarge {
      if (limitPassed || lengthOverLimit()) {
        throw tooLarge();
      }
    }

    /** Fails a read of a successful body whose count passed the limit. */
    private void failIfOverLimit() throws ResponseBodyTooLarge {
      if (count > limit) {
        throw tooLarge();
      }
    }

    /** Adds the bytes of one read to the count, notes the end of the body, and returns them. */
    private int counted(int read) {
      count += Math.max(read, 0);
      ended |= read < 0;
      return read;
    }
  }
}
