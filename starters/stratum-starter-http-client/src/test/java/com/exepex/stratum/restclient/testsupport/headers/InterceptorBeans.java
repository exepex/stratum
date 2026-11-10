package com.exepex.stratum.restclient.testsupport.headers;

import com.exepex.stratum.restclient.StratumClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/** The interceptors, two of them global, their log, and the note converter on the signed client. */
@Configuration(proxyBeanMethods = false)
public class InterceptorBeans {

  /**
   * Returns the log the interceptors write.
   *
   * @return the log
   */
  @Bean
  CallLog callLog() {
    return new CallLog();
  }

  /**
   * Returns the global interceptor.
   *
   * @param log the log
   * @return the interceptor
   */
  @Bean
  @Order(2)
  AuditInterceptor auditInterceptor(CallLog log) {
    return new AuditInterceptor(log);
  }

  /**
   * Returns the second global interceptor, declared after the audit one and ordered before it.
   *
   * @param log the log
   * @return the interceptor
   */
  @Bean
  @Order(1)
  FirstInterceptor firstInterceptor(CallLog log) {
    return new FirstInterceptor(log);
  }

  /**
   * Returns the ordered client's second interceptor.
   *
   * @param log the log
   * @return the interceptor
   */
  @Bean
  StampInterceptor stampInterceptor(CallLog log) {
    return new StampInterceptor(log);
  }

  /**
   * Returns the signed client's own interceptor.
   *
   * @param log the log
   * @return the interceptor
   */
  @Bean
  SigningInterceptor signingInterceptor(CallLog log) {
    return new SigningInterceptor(log);
  }

  /**
   * Adds the note converter to the signed client.
   *
   * @return the customizer
   */
  @Bean
  StratumClientCustomizer noteConverter() {
    return (client, builder) ->
        builder.configureMessageConverters(
            converters -> converters.addCustomConverter(new NoteConverter()));
  }
}
