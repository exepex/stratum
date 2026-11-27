package com.exepex.stratum.resilience.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.resilience.ReactiveRoutesCheck;
import com.exepex.stratum.resilience.ResilienceConstants;
import com.exepex.stratum.resilience.ResilienceProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Import;

/**
 * Stops the startup of a reactive (WebFlux) application that sets routes, unless {@code
 * stratum.resilience.enabled=false}: the limits work only on Spring MVC for now (issue #420).
 */
@StratumInternal
@AutoConfiguration
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(ResilienceConstants.MODULE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@Import(ResilienceSettingsConfiguration.class)
public final class ReactiveRoutesAutoConfiguration {

  /**
   * Checks that no route is set.
   *
   * @param settings the resilience settings
   * @return the check, which stops startup with {@code SX-RESILIENCE-1002} when routes are set
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public ReactiveRoutesCheck stratumReactiveRoutesCheck(ResilienceProperties settings) {
    return new ReactiveRoutesCheck(settings);
  }
}
