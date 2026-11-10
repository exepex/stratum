package com.exepex.stratum.restclient;

import com.exepex.stratum.httpclient.ClientInterceptor;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.util.unit.DataSize;

/**
 * The request factory of a client with {@code copy-headers} or interceptors, around the factory of
 * its transport, the pool's or the mocks'. Each request it makes copies the headers and runs the
 * interceptors when it is sent, after Spring has set the call's own headers and the credential.
 * Spring makes a new request for each attempt, so these steps run on every attempt. Unlike a {@code
 * ClientHttpRequestInterceptor}, it never makes Spring hold the body in memory.
 */
@RequiredArgsConstructor
final class OutgoingStepsRequestFactory implements ClientHttpRequestFactory {

  /** The client's name. */
  private final String client;

  /** The factory of the client's transport. */
  private final ClientHttpRequestFactory transport;

  /** The headers copied from the request the service is answering. */
  private final CopiedHeaders copiedHeaders;

  /**
   * The global interceptors, then the client's own, in the order they run, looked up on the first
   * request, once the application's beans exist.
   */
  private final Supplier<List<ClientInterceptor>> interceptors;

  /** The client's {@code interceptor-body-limit}: the most of a body an interceptor reads. */
  private final DataSize bodyLimit;

  @Override
  public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {
    return new DefaultOutgoingRequest(
        client,
        transport.createRequest(uri, method),
        copiedHeaders,
        interceptors.get(),
        bodyLimit);
  }
}
