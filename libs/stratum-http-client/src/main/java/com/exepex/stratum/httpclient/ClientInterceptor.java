package com.exepex.stratum.httpclient;

/**
 * Changes each call of the clients that name it in {@code @StratumClient(interceptors = ...)},
 * before the call is sent, such as to sign it. Declare it as a bean. It runs on every attempt,
 * after the client's credential is set, so it sees the {@code Authorization} header the call
 * carries.
 *
 * <pre>{@code
 * @Component
 * class OrderSigner implements ClientInterceptor {
 *
 *   @Override
 *   public void beforeSend(OutgoingRequest request) {
 *     request.setHeader("X-Signature", signatureOf(request.body().orElse(new byte[0])));
 *   }
 * }
 * }</pre>
 */
@FunctionalInterface
public interface ClientInterceptor {

  /**
   * Changes one attempt of a call before it is sent. An exception thrown here fails the call before
   * anything is sent; it is neither retried nor counted by the circuit breaker.
   *
   * @param request the attempt about to be sent
   */
  void beforeSend(OutgoingRequest request);
}
