package com.exepex.stratum.web;

import com.exepex.stratum.test.StarterTestSupport;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The shared web library follows Stratum's architecture rules")
class WebArchTest {

  /**
   * The shared web library's types, top-level or nested, that services may use; every other one
   * carries the label.
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
          WebProperties.WebPropertiesBuilder.class,
          WebProperties.BodyLimitSettings.class,
          WebProperties.BodyLimitSettings.BodyLimitSettingsBuilder.class,
          WebProperties.ClientIpSettings.class,
          WebProperties.ClientIpSettings.ClientIpSettingsBuilder.class,
          WebProperties.ClientLocationSettings.class,
          WebProperties.ClientLocationSettings.ClientLocationSettingsBuilder.class,
          WebProperties.CorrelationSettings.class,
          WebProperties.CorrelationSettings.CorrelationSettingsBuilder.class,
          WebProperties.ErrorSettings.class,
          WebProperties.ErrorSettings.ErrorSettingsBuilder.class,
          WebProperties.RequestFieldsSettings.class,
          WebProperties.RequestFieldsSettings.RequestFieldsSettingsBuilder.class,
          WebProperties.RequestLogSettings.class,
          WebProperties.RequestLogSettings.RequestLogSettingsBuilder.class,
          WebProperties.TimeoutSettings.class,
          WebProperties.TimeoutSettings.TimeoutSettingsBuilder.class,
          WebProperties.UserAgentSettings.class,
          WebProperties.UserAgentSettings.UserAgentSettingsBuilder.class,
          WebPropertiesCustomizer.class);

  @Test
  @DisplayName(
      "every class a component scan picks up carries the module switch, and no package cycle")
  void libraryArchitecture() {
    StarterTestSupport.checkLibraryArchitecture(WebConstants.class, API_TYPES);
  }
}
