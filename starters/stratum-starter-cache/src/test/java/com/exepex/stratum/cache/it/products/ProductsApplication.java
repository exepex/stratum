package com.exepex.stratum.cache.it.products;

import com.exepex.stratum.cache.testsupport.ProductCatalog;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** A service that caches product prices and writes no {@code @EnableCaching}. */
@SpringBootApplication
public class ProductsApplication {

  /**
   * Creates the cached catalog.
   *
   * @return the catalog
   */
  @Bean
  ProductCatalog productCatalog() {
    return new ProductCatalog();
  }
}
