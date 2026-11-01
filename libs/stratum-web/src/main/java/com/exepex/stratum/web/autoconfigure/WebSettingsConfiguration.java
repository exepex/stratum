package com.exepex.stratum.web.autoconfigure;

import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.core.env.Environment;

/**
 * Works out the web module's settings once per web application, for every web bean that needs them.
 * The request-field and error configurations of both web starters import it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnStratumModule(WebConstants.MODULE)
@ConditionalOnWebApplication
public class WebSettingsConfiguration {

  /**
   * Gives the web module's settings from {@link WebProperties#resolve}, so the service's {@code
   * WebPropertiesCustomizer} classes win.
   *
   * @param environment the application's environment
   * @return the settings
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public WebProperties stratumWebProperties(Environment environment) {
    return WebProperties.resolve(environment);
  }
}
