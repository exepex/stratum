package com.exepex.stratum.web.testsupport;

import com.exepex.stratum.web.WebProperties;
import com.exepex.stratum.web.WebPropertiesCustomizer;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** A service's Java configuration: {@code ORD-0001} is answered with 410. */
public class GoneOrderWebSettings implements WebPropertiesCustomizer {

  @Override
  public WebProperties customize(WebProperties properties) {
    return properties.toBuilder()
        .error(
            properties.error().toBuilder()
                .statuses(Map.of(OrderErrorCode.DUPLICATE_ORDER.getCode(), HttpStatus.GONE.value()))
                .build())
        .build();
  }
}
