package com.exepex.stratum.webflux;

import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.core.properties.StratumDefaults;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Limits WebFlux multipart uploads, which Spring Boot leaves unlimited, once {@code
 * stratum.web.enabled=true} and {@code stratum.web.backward-compatible=false}: each part may use
 * 10MB of disk and an upload may have 50 parts. A multipart upload sent in chunks is limited by
 * these only, so without them one request could fill the disk. In backward-compatible mode they are
 * left unset, so an upload that worked before Stratum is not refused. Every value the service sets
 * for these Spring Boot keys wins.
 */
public class WebFluxEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** Spring Boot's limits on a multipart upload and the values Stratum gives them. */
  private static final Map<String, Object> MULTIPART_LIMITS =
      Map.of(
          "spring.webflux.multipart.max-disk-usage-per-part",
          "10MB",
          "spring.webflux.multipart.max-parts",
          50);

  /**
   * Runs right after the configuration files are read, so that the module switch is known.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 1;
  }

  /**
   * Adds the multipart limits below the service's own values, when the web module is on and
   * backward-compatible mode is off, as set in files or in a {@code WebPropertiesCustomizer}.
   *
   * @param environment the application's environment
   * @param application the application being started
   */
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (StratumModuleSwitch.isEnabled(environment, WebConstants.MODULE)
        && !WebProperties.resolve(environment).backwardCompatible()) {
      StratumDefaults.addBelowServiceValues(environment, MULTIPART_LIMITS);
    }
  }
}
