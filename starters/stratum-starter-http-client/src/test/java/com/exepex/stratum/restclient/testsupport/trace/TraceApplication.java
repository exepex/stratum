package com.exepex.stratum.restclient.testsupport.trace;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

/** An application whose package holds the traced and third-party clients. */
@SpringBootConfiguration
@EnableAutoConfiguration
public class TraceApplication {

  /**
   * Returns the third party's interceptor.
   *
   * @return the interceptor
   */
  @Bean
  TraceSettingInterceptor traceSettingInterceptor() {
    return new TraceSettingInterceptor();
  }
}
