package com.exepex.stratum.web;

import com.exepex.stratum.test.StarterTestSupport;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The shared web library follows Stratum's architecture rules")
class WebArchTest {

  /**
   * The shared web library's public types that services may use; every other one carries the label.
   */
  private static final Set<Class<?>> API_TYPES =
      Set.of(
          BootErrorBodyCounter.class,
          ClientIpResolver.class,
          ClientLocationLookup.class,
          CorrelationIdChooser.class,
          ErrorResponseFactory.class,
          RequestBodyCounter.class,
          RequestBodyLimit.class,
          RequestBodyLimits.class,
          RequestDeadline.class,
          RequestFieldsPropagation.class,
          RequestFieldsReader.class,
          RequestLogWriter.class,
          RequestTimedOutException.class,
          ResponseTimeout.class,
          ResponseTimeouts.class,
          UserAgentParser.class,
          WebProperties.class,
          WebPropertiesCustomizer.class);

  @Test
  @DisplayName(
      "every class a component scan picks up carries the module switch, and no package cycle")
  void libraryArchitecture() {
    StarterTestSupport.checkLibraryArchitecture(WebConstants.class, API_TYPES);
  }
}
