package com.exepex.stratum.observability.testsupport;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.observability.ObservabilityProperties;
import com.exepex.stratum.observability.ObservabilityPropertiesCustomizer;
import java.util.List;

/**
 * A service's Java configuration that sends its callers' {@code tenantId} baggage on, besides the
 * correlation ID, instead of the names in its files.
 */
public class TenantBaggageObservabilitySettings implements ObservabilityPropertiesCustomizer {

  @Override
  public ObservabilityProperties customize(ObservabilityProperties properties) {
    return properties.toBuilder()
        .baggage(
            new ObservabilityProperties.Baggage(
                List.of(LoggingContextConstants.CORRELATION_ID, BaggageTestData.REMOTE_FIELD)))
        .build();
  }
}
