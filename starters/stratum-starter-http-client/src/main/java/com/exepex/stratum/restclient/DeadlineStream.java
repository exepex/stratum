package com.exepex.stratum.restclient;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A part of an answer, its head or its body, whose every read keeps to its deadline: it fails once
 * the deadline has passed and else waits at most the time left.
 */
class DeadlineStream extends FilterInputStream {

  /** The deadline of the reads. */
  private final ReadDeadline deadline;

  /**
   * Wraps a stream of the connection.
   *
   * @param content the stream as Apache reads it
   * @param deadline the deadline of its reads
   */
  DeadlineStream(InputStream content, ReadDeadline deadline) {
    super(content);
    this.deadline = deadline;
  }

  /**
   * Reads one byte, by the deadline.
   *
   * @return the byte, or -1 at the end
   * @throws IOException when it cannot be read, or not in time
   */
  @Override
  public int read() throws IOException {
    deadline.beforeRead();
    return super.read();
  }

  /**
   * Reads bytes, by the deadline.
   *
   * @param buffer where the bytes go
   * @param offset where in the buffer
   * @param length how many at most
   * @return how many were read, or -1 at the end
   * @throws IOException when they cannot be read, or not in time
   */
  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    deadline.beforeRead();
    return super.read(buffer, offset, length);
  }

  /**
   * Skips bytes, by the deadline.
   *
   * @param count how many at most
   * @return how many were skipped
   * @throws IOException when they cannot be read, or not in time
   */
  @Override
  public long skip(long count) throws IOException {
    deadline.beforeRead();
    return super.skip(count);
  }
}
