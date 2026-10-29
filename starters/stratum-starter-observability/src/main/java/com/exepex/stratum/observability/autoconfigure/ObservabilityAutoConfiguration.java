package com.exepex.stratum.observability.autoconfigure;

import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.observability.ObservabilityConstants;
import com.exepex.stratum.observability.SpanUrlFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Fallback;
import org.springframework.web.util.UriComponentsBuilder;

/** Creates the observability module's beans, when {@code stratum.observability.enabled=true}. */
@AutoConfiguration
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(ObservabilityConstants.MODULE)
public class ObservabilityAutoConfiguration {

  /**
   * Removes query strings and user info from the URLs recorded on spans. Spring Boot applies every
   * {@code ObservationFilter} bean to its observations. Without Spring's web classes the service
   * makes no HTTP client spans, so the filter is not needed.
   *
   * @return the filter
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  @ConditionalOnClass(UriComponentsBuilder.class)
  public SpanUrlFilter spanUrlFilter() {
    return new SpanUrlFilter();
  }
}
