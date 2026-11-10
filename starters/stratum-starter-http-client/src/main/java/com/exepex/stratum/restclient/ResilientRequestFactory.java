package com.exepex.stratum.restclient;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
 * body is sent once, and its body streams as without retries. After the last attempt, Spring gets
 * what it would have got without them: the last answer, unread, or the failure that left no answer.
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

    @Override
    protected ClientHttpResponse executeInternal(HttpHeaders headers, @Nullable Body body)
        throws IOException {
      // The kept answer is closed on every way out but one: handed to Spring as the last answer.
      try (var kept = new KeptAnswer()) {
        return lastAnswer(headers, body, kept);
      } catch (ResourceAccessException noAnswer) {
        // Spring wraps the I/O failure itself, as it does without the retries.
        throw RetryRules.ioFailureOf(noAnswer).orElseThrow(() -> noAnswer);
      }
    }

    /**
     * Runs the call's attempts and returns the last answer: a success, or the last error answer,
     * unread, which Spring's status handlers then read. The answer timeout of each attempt ends by
     * {@code total-timeout}, as a bean cannot tell a download from any other call.
     */
    private ClientHttpResponse lastAnswer(
        HttpHeaders headers, @Nullable Body body, KeptAnswer kept) {
      try {
        return Objects.requireNonNull(
            resilience.call(
                client, method, body != null, false, () -> attempt(headers, body, kept)));
      } catch (RestClientResponseException errorAnswer) {
        return kept.handOver();
      }
    }

    /**
     * Sends one attempt of the call: a fresh request with the call's headers and attributes and the
     * client's credential, and the body, if any. An error answer is kept for Spring and thrown as
     * Spring's exception for it, so that the client's retry and breaker rules decide on it.
     */
    private ClientHttpResponse attempt(HttpHeaders headers, @Nullable Body body, KeptAnswer kept) {
      kept.close();
      try {
        var request = requestFactory.createRequest(uri, method);
        request.getHeaders().putAll(headers);
        request.getAttributes().putAll(getAttributes());
        credential.forEach(initializer -> initializer.initialize(request));
        writeBody(request, body);
        return successOrThrow(request.execute(), kept);
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
     * Returns an answer that is not an error, as Spring's default error handler reads it; keeps a
     * 4xx or 5xx answer unread and throws Spring's typed exception for its status, with its headers
     * and no body.
     */
    private ClientHttpResponse successOrThrow(ClientHttpResponse response, KeptAnswer kept)
        throws IOException {
      var status = response.getStatusCode();
      if (!status.isError()) {
        return response;
      }
      kept.keep(response);
      var text = response.getStatusText();
      throw status.is4xxClientError()
          ? HttpClientErrorException.create(status, text, response.getHeaders(), null, null)
          : HttpServerErrorException.create(status, text, response.getHeaders(), null, null);
    }
  }

  /**
   * The last error answer of one call, kept unread until another attempt replaces it, which closes
   * it, or until Spring is handed it. Closing it frees its connection when it was not handed on.
   */
  private static final class KeptAnswer implements AutoCloseable {

    /** The kept answer, empty when none is kept; one call runs on one thread. */
    private final AtomicReference<@Nullable ClientHttpResponse> answer = new AtomicReference<>();

    /** Keeps an error answer, unread. */
    void keep(ClientHttpResponse errorAnswer) {
      answer.set(errorAnswer);
    }

    /** Returns the kept answer and stops keeping it, so that the caller now closes it. */
    ClientHttpResponse handOver() {
      return Objects.requireNonNull(answer.getAndSet(null));
    }

    /** Closes the kept answer, if any, and keeps none. */
    @Override
    public void close() {
      Optional.ofNullable(answer.getAndSet(null)).ifPresent(ClientHttpResponse::close);
    }
  }
}
