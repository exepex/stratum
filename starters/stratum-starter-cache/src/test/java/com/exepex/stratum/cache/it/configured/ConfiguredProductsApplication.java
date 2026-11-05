package com.exepex.stratum.cache.it.configured;

import com.exepex.stratum.cache.testsupport.ProductCatalog;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;

/** A service that caches product prices and sets caching up itself with a CachingConfigurer. */
@SpringBootApplication
@EnableCaching
public class ConfiguredProductsApplication implements CachingConfigurer {

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
