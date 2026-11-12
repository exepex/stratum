package com.exepex.stratum.restclient;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.test.StarterTestSupport;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The blocking HTTP client starter follows Stratum's architecture rules")
class RestClientArchTest {

  /** The starter's types services use; every other one carries the label. */
  private static final Set<Class<?>> API_TYPES =
      Set.of(
          ClientAttempt.class,
          ClientAttempt.ClientAttemptBuilder.class,
          MockClient.class,
          MockClients.class,
          MockScript.class,
          RecordedCall.class,
          DownstreamException.class,
          StratumClientCustomizer.class,
          StratumHttpClients.class);

  @Test
  @DisplayName(
      "its autoconfigure package only declares beans, it uses only the shared HTTP client library"
          + " and core, and has no cycles")
  void followsStarterArchitecture() {
    StarterTestSupport.checkStarterArchitecture(
        StratumClientGroups.class, API_TYPES, HttpClientConstants.class.getPackageName());
  }
}
