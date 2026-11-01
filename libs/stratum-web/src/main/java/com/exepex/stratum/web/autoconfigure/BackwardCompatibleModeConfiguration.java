package com.exepex.stratum.web.autoconfigure;

import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.web.BackwardCompatibleModeReport;
import com.exepex.stratum.web.BootErrorBodyCounter;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * The startup report and the counter of backward-compatible mode, shared by the error
 * configurations of both web starters.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnStratumModule(WebConstants.MODULE)
@ConditionalOnWebApplication
@Import(WebSettingsConfiguration.class)
public class BackwardCompatibleModeConfiguration {

  /**
   * Logs at startup what backward-compatible mode does, and warns when Spring Boot's error body,
   * which the mode keeps, shows stack traces or exception classes. It cannot be replaced or turned
   * off, so a leak is always reported; a team marks a false positive in its monitoring.
   *
   * @param webProperties the web module's settings
   * @param environment where Spring Boot's error settings are read from
   * @return the report
   */
  @Bean
  public BackwardCompatibleModeReport backwardCompatibleModeReport(
      WebProperties webProperties, Environment environment) {
    return new BackwardCompatibleModeReport(webProperties, environment);
  }

  /**
   * Counts the error answers that kept Spring Boot's body, by route, when Micrometer is on the
   * classpath.
   *
   * @param meterRegistry the service's meter registry, if it has one
   * @return the counter
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
  public BootErrorBodyCounter bootErrorBodyCounter(ObjectProvider<MeterRegistry> meterRegistry) {
    return new BootErrorBodyCounter(meterRegistry);
  }
}
