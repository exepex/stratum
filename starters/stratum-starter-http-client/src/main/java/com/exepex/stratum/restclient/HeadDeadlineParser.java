package com.exepex.stratum.restclient;

import java.io.IOException;
import java.io.InputStream;
import org.apache.hc.client5.http.impl.io.LenientHttpResponseParser;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.io.HttpMessageParserFactory;
import org.apache.hc.core5.http.io.SessionInputBuffer;
import org.jspecify.annotations.Nullable;

/**
 * Apache's own lenient parser of an answer's status line and headers, which reads them by the
 * deadline of the attempt running on this thread, when {@link AnswerDeadline} binds one: a head
 * sent a byte at a time ends with the call's {@code total-timeout}. Apache reads the whole head
 * through the stream it hands the parser, so no read of it escapes the deadline.
 */
final class HeadDeadlineParser extends LenientHttpResponseParser {

  /** Makes the parser of each new connection of a pool. */
  static final HttpMessageParserFactory<ClassicHttpResponse> FACTORY = HeadDeadlineParser::new;

  /**
   * The deadline of the head being read on this thread, bound by {@link AnswerDeadline#readHead}
   * and unbound outside an attempt.
   */
  static final ScopedValue<ReadDeadline> DEADLINE = ScopedValue.newInstance();

  /**
   * Makes the parser of one connection.
   *
   * @param config Apache's limits on a head, or {@code null} for its defaults
   */
  private HeadDeadlineParser(@Nullable Http1Config config) {
    super(config);
  }

  /**
   * Reads the head through a stream that keeps to the deadline bound on this thread, if any.
   *
   * @param buffer Apache's buffer of the connection
   * @param inputStream the connection's stream
   * @return the answer, with its status line and headers
   * @throws IOException when the head cannot be read, or not in time
   * @throws HttpException when the head breaks the protocol
   */
  @Override
  public ClassicHttpResponse parse(SessionInputBuffer buffer, InputStream inputStream)
      throws IOException, HttpException {
    return super.parse(
        buffer, DEADLINE.isBound() ? new DeadlineStream(inputStream, DEADLINE.get()) : inputStream);
  }
}
