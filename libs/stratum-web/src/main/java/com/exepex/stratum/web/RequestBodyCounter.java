package com.exepex.stratum.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.unit.DataSize;

/**
 * Counts the bytes of one request's body as they are read and fails the request once they pass its
 * limit. The web starters wrap the body with it, so a body is stopped as it arrives, before it is
 * held in memory, and its declared {@code Content-Length} is checked before any of it is read. One
 * request reads its body in order, so the counter is not shared between threads at once.
 */
@Slf4j
public final class RequestBodyCounter {

  /** How large the body may be; zero or less for no limit. */
  private DataSize currentLimit;

  /** How many bytes of the body were read so far. */
  private long bytesRead;

  /**
   * Starts counting a request's body.
   *
   * @param limit how large the body may be; zero or less for no limit
   */
  RequestBodyCounter(DataSize limit) {
    this.currentLimit = limit;
  }

  /**
   * Sets how large the body may be, in place of the limit it had, such as its handler's own.
   *
   * @param newLimit the limit; zero or less for no limit
   */
  public void limitTo(DataSize newLimit) {
    currentLimit = newLimit;
  }

  /**
   * Returns how large the body may be.
   *
   * @return the limit; zero or less for none
   */
  public DataSize limit() {
    return currentLimit;
  }

  /**
   * Fails the request if the length its caller declared is larger than the limit.
   *
   * @param contentLength the {@code Content-Length} header's value; negative when there is none
   * @throws RequestBodyTooLargeException if the declared length is larger than the limit
   */
  public void checkDeclaredLength(long contentLength) {
    if (isOverLimit(contentLength)) {
      throw tooLarge();
    }
  }

  /**
   * Counts bytes that were just read, and fails the request once the body is larger than the limit.
   *
   * @param byteCount how many bytes were read
   * @throws RequestBodyTooLargeException if the body is now larger than the limit
   */
  public void count(long byteCount) {
    bytesRead += byteCount;
    if (isOverLimit(bytesRead)) {
      throw tooLarge();
    }
  }

  /** Returns whether a body of this many bytes is larger than the limit. */
  private boolean isOverLimit(long byteCount) {
    return currentLimit.toBytes() > 0 && byteCount > currentLimit.toBytes();
  }

  /** Logs that the body is too large, and returns the exception the request is answered with. */
  private RequestBodyTooLargeException tooLarge() {
    if (log.isWarnEnabled()) {
      log.warn("The request body is larger than its limit of {} bytes", currentLimit.toBytes());
    }
    return new RequestBodyTooLargeException(currentLimit);
  }
}
