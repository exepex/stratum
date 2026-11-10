package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.HttpClientConstants;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.HttpEntityWrapper;

/**
 * Lets at most 64 KB of an error answer's body, status 400 or more, be read, so that a huge error
 * body is never held in memory; an answer that succeeded is untouched. It must be the first handler
 * of Apache's chain, outside the one that undoes gzip, so that it counts the bytes as read and not
 * as sent. Closing the body drains the rest, so that the connection can be used again, but no
 * further than 64 KB in all: a body that does not end within 64 KB is left unread and its
 * connection dropped.
 */
@StratumInternal
public class ErrorBodyLimit implements ExecChainHandler {

  /** The handler's name in Apache's chain. */
  public static final String NAME = "stratum-error-body-limit";

  /**
   * Sends the request on, and limits the body of an error answer.
   *
   * @param request the request
   * @param scope the request's scope
   * @param chain the rest of Apache's chain
   * @return the answer, with at most 64 KB of an error body to read
   * @throws IOException when the request fails
   * @throws HttpException when the answer breaks the protocol
   */
  @Override
  public ClassicHttpResponse execute(
      ClassicHttpRequest request, ExecChain.Scope scope, ExecChain chain)
      throws IOException, HttpException {
    var response = chain.proceed(request, scope);
    if (response.getCode() >= HttpStatus.SC_BAD_REQUEST && response.getEntity() != null) {
      response.setEntity(new LimitedEntity(response.getEntity()));
    }
    return response;
  }

  /**
   * An error body of which at most 64 KB can be read. One count, shared by every stream of the
   * body, holds the bytes the caller read and those drained when the body is closed.
   */
  private static final class LimitedEntity extends HttpEntityWrapper {

    /** The size of the buffer the drain on close reads into. */
    private static final int DRAIN_BUFFER = 8192;

    /** The bytes read so far, by the caller and by the drain. */
    private long count;

    /** Whether the body is known to pass the limit, which leaves Apache's stream unread. */
    private boolean limitPassed;

    /**
     * Wraps the body.
     *
     * @param entity the error answer's body
     */
    private LimitedEntity(HttpEntity entity) {
      super(entity);
    }

    /**
     * Returns the body, which ends after 64 KB.
     *
     * @return the body
     * @throws IOException when it cannot be read
     */
    @Override
    public InputStream getContent() throws IOException {
      return new LimitedStream(super.getContent());
    }

    /**
     * Returns the length when it is within the limit, or unknown.
     *
     * @return the length, or -1
     */
    @Override
    public long getContentLength() {
      var length = super.getContentLength();
      return length <= HttpClientConstants.ERROR_BODY_LIMIT ? length : -1;
    }

    /**
     * Writes at most 64 KB of the body.
     *
     * @param out where the body is written
     * @throws IOException when it cannot be read or written
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

    /** Returns how many bytes may still be read. */
    private long left() {
      return Math.max(0, HttpClientConstants.ERROR_BODY_LIMIT - count);
    }

    /** A stream that ends after 64 KB and drains the rest, up to 64 KB, when closed. */
    private final class LimitedStream extends FilterInputStream {

      /**
       * Wraps the stream.
       *
       * @param content the error answer's body
       */
      private LimitedStream(InputStream content) {
        super(content);
      }

      /**
       * Reads one byte, or ends after the limit.
       *
       * @return the byte, or -1 at the end
       * @throws IOException when it cannot be read
       */
      @Override
      public int read() throws IOException {
        if (left() <= 0) {
          return -1;
        }
        var read = super.read();
        count += read < 0 ? 0 : 1;
        return read;
      }

      /**
       * Reads bytes, never past the limit.
       *
       * @param buffer where the bytes go
       * @param offset where in the buffer
       * @param length how many at most
       * @return how many were read, or -1 at the end
       * @throws IOException when it cannot be read
       */
      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
        if (left() <= 0) {
          return -1;
        }
        var read = super.read(buffer, offset, (int) Math.min(length, left()));
        count += Math.max(read, 0);
        return read;
      }

      /**
       * Skips bytes, never past the limit.
       *
       * @param skipped how many at most
       * @return how many were skipped
       * @throws IOException when it cannot be read
       */
      @Override
      public long skip(long skipped) throws IOException {
        var done = super.skip(Math.min(skipped, left()));
        count += done;
        return done;
      }

      /**
       * Returns how many bytes can be read without blocking, never past the limit.
       *
       * @return the count
       * @throws IOException when it cannot be read
       */
      @Override
      public int available() throws IOException {
        return (int) Math.min(super.available(), left());
      }

      /**
       * Drains the rest of the body, then closes Apache's stream, so that the connection is used
       * again, but reads no more than 64 KB in all: a body whose {@code Content-Length} is over 64
       * KB, or whose end is not reached within 64 KB, is left unread, so its connection is dropped,
       * and the close does not fail. A failed read, such as a read timeout, also leaves it unread
       * and is thrown; Spring ignores a failure to close an answer.
       *
       * @throws IOException when the drain cannot read the body
       */
      @Override
      public void close() throws IOException {
        if (limitPassed
            || LimitedEntity.super.getContentLength() > HttpClientConstants.ERROR_BODY_LIMIT) {
          limitPassed = true;
          return;
        }
        var buffer = new byte[DRAIN_BUFFER];
        try {
          // A loop, as each read may end the drain early; none reads past the limit.
          while (left() > 0) {
            var read = in.read(buffer, 0, (int) Math.min(buffer.length, left()));
            if (read < 0) {
              in.close();
              return;
            }
            count += read;
          }
        } catch (IOException failure) {
          limitPassed = true;
          throw failure;
        }
        limitPassed = true;
      }
    }
  }
}
