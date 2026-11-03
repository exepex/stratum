package com.exepex.stratum.webmvc.testsupport;

import com.exepex.stratum.webmvc.autoconfigure.WebMvcRequestFieldsAutoConfiguration;
import lombok.experimental.UtilityClass;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** Starts the servlet applications that the web MVC starter's request fields tests share. */
@UtilityClass
public class WebMvcRequestFieldsRunners {

  /**
   * Returns a runner for a servlet application with Stratum's request fields auto-configuration.
   *
   * @return a new runner, with the module left unset
   */
  public WebApplicationContextRunner requestFieldsRunner() {
    return new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WebMvcRequestFieldsAutoConfiguration.class));
  }
}
