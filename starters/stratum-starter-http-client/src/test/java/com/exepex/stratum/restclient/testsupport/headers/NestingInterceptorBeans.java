package com.exepex.stratum.restclient.testsupport.headers;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/** Declares the interceptor that calls the lone client from within the signed client's calls. */
@Configuration(proxyBeanMethods = false)
public class NestingInterceptorBeans {

  /**
   * Returns the nesting interceptor, ordered before every other global one.
   *
   * @param keys the lone client
   * @return the interceptor
   */
  @Bean
  @Order(0)
  NestingInterceptor nestingInterceptor(LoneClient keys) {
    return new NestingInterceptor(keys);
  }
}
