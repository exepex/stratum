package com.exepex.stratum.restclient;

import com.exepex.stratum.httpclient.ClientSettings;
import org.springframework.web.client.RestClient;

/**
 * Gives clients that Stratum did not build, such as a generated client that takes a {@code
 * RestClient} or a vendor SDK with its own engine, a Stratum client's settings. Inject it as {@code
 * stratumHttpClients}.
 */
public interface StratumHttpClients {

  /**
   * Returns the {@code RestClient} of a client: its address, timeouts and connection pool, which it
   * shares with the client's interfaces, and the service's customizers. It sends each call once; it
   * does not retry.
   *
   * @param client the client's name, as under {@code stratum.http-client.clients}
   * @return the client, the same one on every call
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-HTTPC-1004} if the client has
   *     no address
   */
  RestClient restClient(String client);

  /**
   * Returns the settings of a client, its own merged with the shared defaults, for a client with
   * its own engine.
   *
   * @param client the client's name, as under {@code stratum.http-client.clients}
   * @return the client's settings
   */
  ClientSettings settings(String client);
}
