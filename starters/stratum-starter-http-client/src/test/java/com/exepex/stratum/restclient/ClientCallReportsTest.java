package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.restclient.testsupport.RecordedMeters;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

@DisplayName("The reports of the clients' calls look the meters up once, on first use")
class ClientCallReportsTest {

  /** The meters the reports count in. */
  private final RecordedMeters meters = new RecordedMeters();

  /** How many times the reports asked Spring for the meters. */
  private final AtomicInteger lookups = new AtomicInteger();

  /** Spring's provider of the meters, counting each lookup. */
  private final ObjectProvider<ClientMeters> provider =
      new ObjectProvider<>() {
        @Override
        public ClientMeters getIfAvailable() {
          lookups.incrementAndGet();
          return meters;
        }
      };

  /** The reports under test. */
  private final ClientCallReports reports =
      new ClientCallReports(
          new ClientCallLog(HttpClientProperties.builder().build(), null), provider);

  @Test
  @DisplayName("no lookup before the first report, then one lookup for all the reports")
  void metersLookedUpOnce() {
    assertThat(lookups).hasValue(0);

    reports.poolRefused(RestClientTestData.INVENTORY);
    reports.poolRefused(RestClientTestData.INVENTORY);

    assertThat(meters.getPoolRefusals())
        .containsExactly(RestClientTestData.INVENTORY, RestClientTestData.INVENTORY);
    assertThat(lookups).hasValue(1);
  }
}
