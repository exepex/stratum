package com.exepex.stratum.webmvc.it;

import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A service whose handlers put a field in the logging context and never remove it. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(LeftoverFieldsApplication.CustomersController.class)
public class LeftoverFieldsApplication {

  /** The field the handlers put and forget. */
  static final String CUSTOMER_ID = "customerId";

  /** What the reading endpoint answers when the thread holds no customer. */
  static final String NO_CUSTOMER = "none";

  /** Handlers that leave the customer behind, and one that reads what the thread holds. */
  @RestController
  static class CustomersController {

    /**
     * Puts the customer in the logging context and answers without removing it.
     *
     * @param customerId the customer
     * @return the customer
     */
    @GetMapping("/customers/{customerId}")
    String leaveCustomer(@PathVariable String customerId) {
      MDC.put(CUSTOMER_ID, customerId);
      return customerId;
    }

    /**
     * Puts the customer in the logging context and fails.
     *
     * @param customerId the customer
     * @return never
     */
    @GetMapping("/customers/{customerId}/failing")
    String failWithCustomer(@PathVariable String customerId) {
      MDC.put(CUSTOMER_ID, customerId);
      throw new IllegalStateException("handler failed");
    }

    /**
     * Returns the customer the thread holds when this request starts.
     *
     * @return the customer, or {@code none}
     */
    @GetMapping("/customer-on-thread")
    String customerOnThread() {
      return Objects.requireNonNullElse(MDC.get(CUSTOMER_ID), NO_CUSTOMER);
    }
  }
}
