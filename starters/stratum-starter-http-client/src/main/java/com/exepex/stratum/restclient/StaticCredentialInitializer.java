package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestInitializer;

/**
 * Sets a client's fixed credential, an API key, a bearer token or Basic credentials, on every
 * request it creates, so on every attempt. It sets a header only, so a streamed body is never
 * buffered. Its value is a secret and is never shown.
 */
@StratumInternal
@RequiredArgsConstructor
public class StaticCredentialInitializer implements ClientHttpRequestInitializer {

  /** The header that carries the credential, such as Authorization or X-Api-Key. */
  private final String headerName;

  /** The header's value, a secret. */
  private final String headerValue;

  /**
   * Sets the credential's header, replacing any earlier value.
   *
   * @param request the request about to be sent
   */
  @Override
  public void initialize(ClientHttpRequest request) {
    request.getHeaders().set(headerName, headerValue);
  }
}
