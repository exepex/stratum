package com.exepex.stratum.restclient;

import com.exepex.stratum.core.error.FailureCauses;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serial;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Holds the bytes of a request body an interceptor reads, up to the client's {@code
 * interceptor-body-limit}: the write that would pass the limit fails, so no more than the limit is
 * ever accepted. The failure may come back wrapped by a message converter; {@link
 * #isLimitReachedIn} finds it in the causes.
 */
@RequiredArgsConstructor
final class LimitedBodyStream extends OutputStream {

  /** The most bytes accepted. */
  private final long limit;

  /** The bytes accepted so far. */
  private final ByteArrayOutputStream accepted = new ByteArrayOutputStream();

  /** Whether a write was refused for passing the limit, whatever became of the refusal. */
  @Getter(AccessLevel.PACKAGE)
  private boolean limitReached;

  @Override
  public void write(int oneByte) throws IOException {
    refuseBeyondLimit(1);
    accepted.write(oneByte);
  }

  @Override
  public void write(byte[] bytes, int offset, int length) throws IOException {
    refuseBeyondLimit(length);
    accepted.write(bytes, offset, length);
  }

  /** Frees nothing: the bytes are held in memory until the stream is dropped. */
  @Override
  public void close() {
    // Nothing to free.
  }

  /**
   * Returns the bytes accepted.
   *
   * @return a copy of the bytes
   */
  byte[] toByteArray() {
    return accepted.toByteArray();
  }

  /**
   * Returns whether a failure, or one of its causes, is this stream refusing to pass its limit.
   *
   * @param failure the failure of a write to this stream
   * @return whether the limit was reached
   */
  boolean isLimitReachedIn(Throwable failure) {
    return FailureCauses.causesOf(failure).anyMatch(BodyLimitExceeded.class::isInstance);
  }

  /** Fails, before anything is accepted, when the bytes would pass the limit. */
  private void refuseBeyondLimit(int length) throws BodyLimitExceeded {
    if (accepted.size() + (long) length > limit) {
      limitReached = true;
      throw new BodyLimitExceeded();
    }
  }

  /** The signal that a write would pass the limit; it carries no byte of the body. */
  private static final class BodyLimitExceeded extends IOException {

    /** The version of this exception's serialized form. */
    @Serial private static final long serialVersionUID = 1L;

    /** Makes the signal. */
    BodyLimitExceeded() {
      super("The request body is larger than the client's interceptor-body-limit");
    }
  }
}
