package com.exepex.stratum.restclient;

/**
 * Scripts the answers of clients set to {@code mock: true} and shows the calls they received, in
 * tests. Inject it as {@code mockClients}. Scripts and calls are cleared after each test method.
 */
public interface MockClients {

  /**
   * Returns the mock of the client an interface belongs to, and starts recording its calls.
   *
   * @param type a {@code @StratumClient} interface, or one named in a client's {@code interfaces}
   * @param <T> the interface
   * @return the client's mock
   * @throws IllegalArgumentException if the interface's client is not set to {@code mock: true}
   */
  <T> MockClient<T> of(Class<T> type);

  /** Clears every script and recorded call, and ends any delayed answer at once. */
  void reset();
}
