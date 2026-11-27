package com.exepex.stratum.resilience.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.properties.StratumCustomizers;
import com.exepex.stratum.resilience.ResilienceConstants;
import com.exepex.stratum.resilience.ResilienceProperties;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.core.env.Environment;

/**
 * Works out the resilience settings once per web application, for the servlet limits and the
 * reactive check, which both import it. An application with no web server reads no setting.
 */
@StratumInternal
@Configuration(proxyBeanMethods = false)
@ConditionalOnStratumModule(ResilienceConstants.MODULE)
@ConditionalOnWebApplication
public class ResilienceSettingsConfiguration {

  /**
   * Stops startup with {@code SX-CORE-1004} when the service declares a resilience customizer as a
   * bean, which would be silently ignored.
   *
   * @param beanFactory the service's beans
   */
  public ResilienceSettingsConfiguration(ListableBeanFactory beanFactory) {
    StratumCustomizers.rejectBeans(beanFactory);
  }

  /**
   * Gives the resilience settings from {@link ResilienceProperties#resolve}, so the service's
   * {@code ResiliencePropertiesCustomizer} classes win.
   *
   * @param environment the application's environment
   * @return the settings
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public ResilienceProperties stratumResilienceProperties(Environment environment) {
    return ResilienceProperties.resolve(environment);
  }
}
