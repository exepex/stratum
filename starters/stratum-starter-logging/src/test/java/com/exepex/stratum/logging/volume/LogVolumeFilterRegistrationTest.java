package com.exepex.stratum.logging.volume;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.LoggingProperties.Volume;
import com.exepex.stratum.logging.testsupport.LogVolumeTestData;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LogVolumeFilterRegistration keeps one volume filter in Logback, the latest one")
class LogVolumeFilterRegistrationTest {

  /** The default settings, with the limits on. */
  private final Volume limitsOn = LogVolumeTestData.defaults().build();

  /** Returns a registration of a new filter with the settings. */
  private LogVolumeFilterRegistration registration(Volume settings) {
    return new LogVolumeFilterRegistration(
        new LogVolumeFilter(settings, Clock.systemUTC()), settings);
  }

  /** Leaves no volume filter in Logback for the next test. */
  @AfterEach
  void removeVolumeFilters() {
    LogVolumeTestData.jvmLogbackContext()
        .getTurboFilterList()
        .removeIf(LogVolumeFilter.class::isInstance);
  }

  @Test
  @DisplayName("starting adds the filter and stopping removes it")
  void addsAndRemovesItsFilter() {
    var registration = registration(limitsOn);

    registration.start();
    assertThat(LogVolumeTestData.volumeFiltersInLogback()).hasSize(1);
    assertThat(registration.isRunning()).isTrue();
    registration.stop();

    assertThat(LogVolumeTestData.volumeFiltersInLogback()).isEmpty();
    assertThat(registration.isRunning()).isFalse();
  }

  @Test
  @DisplayName(
      "a later application's filter replaces the earlier one, which stops without removing it")
  void laterApplicationReplacesTheEarlier() {
    var earlier = registration(limitsOn);
    var later = registration(limitsOn);

    earlier.start();
    later.start();
    var laterFilters = LogVolumeTestData.volumeFiltersInLogback();
    earlier.stop();

    assertThat(laterFilters).hasSize(1);
    assertThat(LogVolumeTestData.volumeFiltersInLogback()).isEqualTo(laterFilters);
    later.stop();
    assertThat(LogVolumeTestData.volumeFiltersInLogback()).isEmpty();
  }

  @Test
  @DisplayName("with the limits off, nothing is added and an earlier filter stays")
  void limitsOffAddNothing() {
    var earlier = registration(limitsOn);
    var limitsOff = registration(LogVolumeTestData.defaults().enabled(false).build());

    earlier.start();
    var earlierFilters = LogVolumeTestData.volumeFiltersInLogback();
    limitsOff.start();
    limitsOff.stop();

    assertThat(LogVolumeTestData.volumeFiltersInLogback()).hasSize(1).isEqualTo(earlierFilters);
  }
}
