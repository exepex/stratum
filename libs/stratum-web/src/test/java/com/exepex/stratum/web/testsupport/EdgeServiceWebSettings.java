package com.exepex.stratum.web.testsupport;

import com.exepex.stratum.web.WebProperties;
import com.exepex.stratum.web.WebPropertiesCustomizer;

/** A service's Java configuration for a service that faces the internet: no caller IDs. */
public class EdgeServiceWebSettings implements WebPropertiesCustomizer {

  @Override
  public WebProperties customize(WebProperties properties) {
    return properties.toBuilder()
        .correlation(properties.correlation().toBuilder().acceptFromCaller(false).build())
        .build();
  }
}
