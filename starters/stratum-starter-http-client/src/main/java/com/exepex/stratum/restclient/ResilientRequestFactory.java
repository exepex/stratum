package com.exepex.stratum.restclient;

import com.exepex.stratum.httpclient.HttpClientConstants;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.AbstractStreamingClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInitializer;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The request factory of a client's {@code RestClient} and {@code RestTemplate} beans: it runs each
 * call through the client's retries and circuit breaker ({@link ClientResilience#call}). Each
 * attempt is a fresh request from the client's own factory, with the call's headers and the
 * client's credential, so its pool wait and timeouts end by {@code total-timeout}. A call with a
 * body is sent once, and its body streams as without retries. An error answer is read, at most 64
 * KB, and its connection freed at once, before any wait for the next attempt. After the last
 * attempt, Spring gets what it would have got without them: a success answer, unread, the last
 * error answer, or the failure that left no answer.
 */
@RequiredArgsConstructor
final class ResilientRequestFactory implements ClientHttpRequestFactory {

  /** The client's name. */
  private final String client;

  /** The client's own request factory: its pool, or its mocks. */
  private final ClientHttpRequestFactory requestFactory;

  /** What sets the client's credential on each attempt's request, empty when it sends none. */
  private final List<ClientHttpRequestInitializer> credential;

  /** The retries and circuit breakers of every client. */
  private final ClientResilience resilience;

  @Override
  public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
    return new ResilientRequest(uri, method);
  }

  /** One call of the client, which opens nothing until it is executed. */
  @RequiredArgsConstructor
  private final class ResilientRequest extends AbstractStreamingClientHttpRequest {

    /** Where the call goes. */
    private final URI uri;

    /** The call's HTTP method. */
    @Getter private final HttpMethod method;

    @Override
    public URI getURI() {
      return uri;
    }

    /**
     * Runs the call's attempts and returns the last answer: a success, unread, or the copy of the
     * last error answer, which Spring's status handlers then read. The answer timeout of each
     * attempt ends by {@code total-timeout}, as a bean cannot tell a download from any other call.
     */
    @Override
    protected ClientHttpResponse executeInternal(HttpHeaders headers, @Nullable Body body)
        throws IOException {
      var lastErrorAnswer = new AtomicReference<@Nullable ClientHttpResponse>();
      try {
        return Objects.requireNonNull(
            resilience.call(
                client,
                method,
                body != null,
                false,
                () -> attempt(headers, body, lastErrorAnswer)));
      } catch (RestClientResponseException _) {
        return Objects.requireNonNull(lastErrorAnswer.get());
      } catch (ResourceAccessException noAnswer) {
        // Spring wraps the I/O failure itself, as it does without the retries.
        throw RetryRules.ioFailureOf(noAnswer).orElseThrow(() -> noAnswer);
      }
    }

    /**
     * Sends one attempt of the call: a fresh request with the call's headers and the client's
     * credential, and the body, if any. An error answer is kept as a copy for Spring and thrown as
     * Spring's exception for it, so that the client's retry and breaker rules decide on it.
     */
    private ClientHttpResponse attempt(
        HttpHeaders headers,
        @Nullable Body body,
        AtomicReference<@Nullable ClientHttpResponse> lastErrorAnswer) {
      try {
        var request = requestFactory.createRequest(uri, method);
        request.getHeaders().putAll(headers);
        credential.forEach(initializer -> initializer.initialize(request));
        writeBody(request, body);
        return successOrThrow(request.execute(), lastErrorAnswer);
      } catch (IOException failure) {
        throw new ResourceAccessException("An attempt of the call got no answer", failure);
      }
    }

    /**
     * Hands the body on as the attempt's request takes it: streamed to the pool's request, written
     * to any other, such as a mocked one.
     */
    private void writeBody(ClientHttpRequest request, @Nullable Body body) throws IOException {
      if (body == null) {
        return;
      }
      if (request instanceof StreamingHttpOutputMessage streaming) {
        streaming.setBody(body);
      } else {
        body.writeTo(request.getBody());
      }
    }

    /**
     * Returns an answer that is not an error, unread, as Spring's default error handler reads it;
     * copies a 4xx or 5xx answer and closes it at once, freeing its connection, then throws
     * Spring's typed exception for its status, with its headers.
     */
    private ClientHttpResponse successOrThrow(
        ClientHttpResponse response, AtomicReference<@Nullable ClientHttpResponse> lastErrorAnswer)
        throws IOException {
      if (!response.getStatusCode().isError()) {
        return response;
      }
      var copy = CopiedAnswer.of(response);
      lastErrorAnswer.set(copy);
      var status = copy.getStatusCode();
      throw status.is4xxClientError()
          ? HttpClientErrorException.create(
              status, copy.getStatusText(), copy.getHeaders(), null, null)
          : HttpServerErrorException.create(
              status, copy.getStatusText(), copy.getHeaders(), null, null);
    }
  }

  /**
   * An error answer held in memory: its status, headers and at most 64 KB of its body, the most the
   * pool lets a caller read of an error body.
   *
   * @param statusCode the answer's status
   * @param statusText the answer's status text
   * @param headers the answer's headers
   * @param body the answer's body, at most 64 KB
   */
  @Builder
  private record CopiedAnswer(
      HttpStatusCode statusCode, String statusText, HttpHeaders headers, ByteArrayResource body)
      implements ClientHttpResponse {

    /**
     * Copies an answer, then closes it, which frees its connection.
     *
     * @param answer the answer
     * @return the copy
     * @throws IOException if the answer cannot be read
     */
    static CopiedAnswer of(ClientHttpResponse answer) throws IOException {
      try (answer) {
        return CopiedAnswer.builder()
            .statusCode(answer.getStatusCode())
            .statusText(answer.getStatusText())
            .headers(HttpHeaders.copyOf(answer.getHeaders()))
            .body(
                new ByteArrayResource(
                    answer.getBody().readNBytes(HttpClientConstants.ERROR_BODY_LIMIT)))
            .build();
      }
    }

    @Override
    public HttpStatusCode getStatusCode() {
      return statusCode;
    }

    @Override
    public String getStatusText() {
      return statusText;
    }

    @Override
    public HttpHeaders getHeaders() {
      return HttpHeaders.readOnlyHttpHeaders(headers);
    }

    @Override
    public InputStream getBody() throws IOException {
      return body.getInputStream();
    }

    @Override
    public void close() {
      // Nothing to free: the answer is held in memory, and its connection was freed when copied.
    }
  }
}
