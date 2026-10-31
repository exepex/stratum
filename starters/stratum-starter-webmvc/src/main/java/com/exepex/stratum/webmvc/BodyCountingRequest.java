package com.exepex.stratum.webmvc;

import com.exepex.stratum.web.RequestBodyCounter;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/**
 * A request whose body is counted by a {@link RequestBodyCounter} as the handler reads it, through
 * {@link #getInputStream()} or {@link #getReader()}. The server reads form fields and multipart
 * parts itself, without this wrapper; their declared length is checked instead.
 */
class BodyCountingRequest extends HttpServletRequestWrapper {

  /** Counts the body. */
  private final RequestBodyCounter counter;

  /**
   * Wraps the request.
   *
   * @param request the request
   * @param counter counts its body
   */
  BodyCountingRequest(HttpServletRequest request, RequestBodyCounter counter) {
    super(request);
    this.counter = counter;
  }

  /**
   * Returns the body, counted as it is read.
   *
   * @return the body
   * @throws IOException if the body cannot be read
   */
  @Override
  public ServletInputStream getInputStream() throws IOException {
    return new CountingInputStream(super.getInputStream(), counter);
  }

  /**
   * Returns the body as text in the request's character set, counted as it is read.
   *
   * @return the body's reader
   * @throws IOException if the body cannot be read
   */
  @Override
  public BufferedReader getReader() throws IOException {
    var charset =
        Optional.ofNullable(getCharacterEncoding())
            .map(Charset::forName)
            .orElse(StandardCharsets.ISO_8859_1);
    return new BufferedReader(new InputStreamReader(getInputStream(), charset));
  }

  /** The body's stream, which counts each byte it hands on. */
  @RequiredArgsConstructor
  private static final class CountingInputStream extends ServletInputStream {

    /** The server's stream. */
    private final ServletInputStream body;

    /** Counts the bytes. */
    private final RequestBodyCounter counter;

    @Override
    public int read() throws IOException {
      var nextByte = body.read();
      if (nextByte >= 0) {
        counter.count(1);
      }
      return nextByte;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      var byteCount = body.read(buffer, offset, length);
      if (byteCount > 0) {
        counter.count(byteCount);
      }
      return byteCount;
    }

    @Override
    public boolean isFinished() {
      return body.isFinished();
    }

    @Override
    public boolean isReady() {
      return body.isReady();
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      body.setReadListener(readListener);
    }
  }
}
