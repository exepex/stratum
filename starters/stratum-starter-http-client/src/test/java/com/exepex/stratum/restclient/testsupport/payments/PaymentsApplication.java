package com.exepex.stratum.restclient.testsupport.payments;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

/** An application whose package holds only the payments client, with the team's set-up. */
@SpringBootConfiguration
@EnableAutoConfiguration
public class PaymentsApplication {

  /**
   * The team's set-up of its clients.
   *
   * @return the set-up
   */
  @Bean
  PaymentsSetup paymentsSetup() {
    return new PaymentsSetup();
  }
}
