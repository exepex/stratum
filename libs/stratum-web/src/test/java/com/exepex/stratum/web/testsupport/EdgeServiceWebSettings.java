package com.exepex.stratum.web.testsupport;

import com.exepex.stratum.web.WebProperties;
import com.exepex.stratum.web.WebPropertiesCustomizer;
import java.time.Duration;
import org.springframework.util.unit.DataSize;

/**
 * A service's Java configuration for a service that faces the internet: no caller IDs, 2 seconds
 * and a 16KB body for each request.
 */
public class EdgeServiceWebSettings implements WebPropertiesCustomizer {

  @Override
  public WebProperties customize(WebProperties properties) {
    return properties.toBuilder()
        .correlation(properties.correlation().toBuilder().acceptFromCaller(false).build())
        .timeout(properties.timeout().toBuilder().defaultLimit(Duration.ofSeconds(2)).build())
        .bodyLimit(properties.bodyLimit().toBuilder().defaultLimit(DataSize.parse("16KB")).build())
        .build();
  }
}
