package com.exepex.stratum.cache.it.orders;

import com.exepex.stratum.cache.testsupport.values.OrderCatalog;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** A service that caches orders in Redis, with its value types in an allowed package. */
@SpringBootApplication
public class OrdersApplication {

  /**
   * Creates the cached catalog.
   *
   * @return the catalog
   */
  @Bean
  OrderCatalog orderCatalog() {
    return new OrderCatalog();
  }
}
