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
 * as sent. Closing the body closes Apache's own stream, which reads and drops the rest so that the
 * connection can be used again.
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

  /** An error body of which at most 64 KB can be read. */
  private static final class LimitedEntity extends HttpEntityWrapper {

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
  }

  /** A stream that ends after 64 KB and closes the stream it reads. */
  private static final class LimitedStream extends FilterInputStream {

    /** How many bytes may still be read. */
    private int left = HttpClientConstants.ERROR_BODY_LIMIT;

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
      if (left <= 0) {
        return -1;
      }
      var read = super.read();
      left -= read < 0 ? 0 : 1;
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
      if (left <= 0) {
        return -1;
      }
      var read = super.read(buffer, offset, Math.min(length, left));
      left -= Math.max(read, 0);
      return read;
    }

    /**
     * Skips bytes, never past the limit.
     *
     * @param count how many at most
     * @return how many were skipped
     * @throws IOException when it cannot be read
     */
    @Override
    public long skip(long count) throws IOException {
      var skipped = super.skip(Math.min(count, left));
      left -= (int) skipped;
      return skipped;
    }

    /**
     * Returns how many bytes can be read without blocking, never past the limit.
     *
     * @return the count
     * @throws IOException when it cannot be read
     */
    @Override
    public int available() throws IOException {
      return Math.min(super.available(), left);
    }
  }
}
