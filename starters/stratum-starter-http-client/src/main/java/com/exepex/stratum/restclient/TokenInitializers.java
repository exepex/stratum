package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.ClientSettings;
import java.util.Optional;
import org.springframework.http.client.ClientHttpRequestInitializer;

/**
 * Makes the request initializer of a client whose auth is a token from Spring Security: the
 * service's own, or the caller's. Each kind is a bean that exists only when its Spring Security
 * library is present, so that the rest of the starter never loads those libraries.
 */
@StratumInternal
@FunctionalInterface
public interface TokenInitializers {

  /**
   * Returns the initializer that sends the client's token, if the client's auth is this kind.
   *
   * @param client the client's name
   * @param settings the client's settings, with an auth already validated to hold one kind
   * @return the initializer, or empty for another kind
   */
  Optional<ClientHttpRequestInitializer> initializerFor(String client, ClientSettings settings);
}
