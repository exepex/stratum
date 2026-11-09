package com.exepex.stratum.restclient;

import com.exepex.stratum.httpclient.HttpClientConstants;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

/**
 * The request factory of a client set to {@code mock: true}, in place of its connection pool. Its
 * requests open no connection: each reads and drops the body it is given and answers from {@link
 * MockAnswers}, so Spring's status handlers and converters read the answer as a real one. A delay
 * longer than the call's time limit waits that limit and fails as a read timeout would.
 */
@RequiredArgsConstructor
final class MockRequestFactory implements ClientHttpRequestFactory {

  /** The client's name. */
  private final String client;

  /** The client's {@code timeout}, the longest wait for an answer. */
  private final Duration timeout;

  /** The answers of every mocked client. */
  private final MockAnswers answers;

  @Override
  public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
    return new MockRequest(uri, method);
  }

  /** A request that answers from the mocks. */
  @RequiredArgsConstructor
  private final class MockRequest extends AbstractClientHttpRequest {

    /** Where the call goes. */
    private final URI uri;

    /** The call's HTTP method. */
    @Getter private final HttpMethod method;

    /**
     * Waits an answer's delay, or the call's time limit and fails when the delay is longer. The
     * limit is {@code timeout}, cut to the time {@code total-timeout} leaves, except for a streamed
     * download, which keeps its full {@code timeout} as with a real service.
     */
    private void await(Duration delay) throws IOException {
      var limit =
          AttemptTimeLeft.keepsAnswerTimeout()
              ? timeout
              : AttemptTimeLeft.timeLeft()
                  .filter(timeLeft -> timeLeft.compareTo(timeout) < 0)
                  .orElse(timeout);
      if (delay.compareTo(limit) <= 0) {
        answers.pause(delay);
        return;
      }
      answers.pause(limit);
      throw new SocketTimeoutException(
          "The mocked answer of client " + client + " takes longer than the call's time limit");
    }

    @Override
    public URI getURI() {
      return uri;
    }

    @Override
    protected OutputStream getBodyInternal(HttpHeaders headers) {
      return OutputStream.nullOutputStream();
    }

    @Override
    protected ClientHttpResponse executeInternal(HttpHeaders headers) throws IOException {
      var call =
          Optional.ofNullable(getAttributes().get(MockAnswers.METHOD_ATTRIBUTE))
              .filter(MockCall.class::isInstance)
              .map(MockCall.class::cast)
              .orElse(null);
      var answer = answers.answer(client, call);
      await(answer.delay());
      return new MockResponse(answer);
    }
  }

  /**
   * The answer of a mocked call.
   *
   * @param answer the answer
   */
  private record MockResponse(MockAnswer answer) implements ClientHttpResponse {

    @Override
    public HttpStatusCode getStatusCode() {
      return answer.status();
    }

    @Override
    public String getStatusText() {
      var known = HttpStatus.resolve(answer.status().value());
      return known == null ? "" : known.getReasonPhrase();
    }

    @Override
    public HttpHeaders getHeaders() {
      var headers = new HttpHeaders();
      Optional.ofNullable(answer.contentType()).ifPresent(headers::setContentType);
      return headers;
    }

    @Override
    public InputStream getBody() {
      var bytes = answer.body().getBytes(StandardCharsets.UTF_8);
      // An error body is cut as a real one is (ErrorBodyLimit), so a test sees what production
      // sees.
      var length =
          answer.status().isError()
              ? Math.min(bytes.length, HttpClientConstants.ERROR_BODY_LIMIT)
              : bytes.length;
      return new ByteArrayInputStream(bytes, 0, length);
    }

    @Override
    public void close() {
      // Nothing to free: the answer is held in memory.
    }
  }
}
