package com.exepex.stratum.restclient.testsupport.headers;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares a global interceptor whose bean needs a Stratum client. */
@Configuration(proxyBeanMethods = false)
public class ClientUsingInterceptorBeans {

  /**
   * Returns the interceptor that needs the plain client.
   *
   * @param keys the client it fetches its key through
   * @param log the log
   * @return the interceptor
   */
  @Bean
  ClientUsingInterceptor clientUsingInterceptor(PlainClient keys, CallLog log) {
    return new ClientUsingInterceptor(keys, log);
  }
}
