package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.opentelemetry.api.baggage.Baggage;
import lombok.extern.java.Log;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@Log(topic = OpenTelemetryUsageIssues.USAGE_LOGGER_NAME)
@DisplayName("OpenTelemetryUsageIssues captures each wrong use of OpenTelemetry's API")
class OpenTelemetryUsageIssuesTest {

  /** The baggage key the test puts a null value under. */
  private static final String CORRELATION_ID = "correlationId";

  /** Puts a null baggage value twice, which OpenTelemetry reports as two wrong uses. */
  private Baggage withTwoNullValues() {
    Baggage.empty().toBuilder().put(CORRELATION_ID, null).build();
    return Baggage.empty().toBuilder().put(CORRELATION_ID, null).build();
  }

  /** Fails, as work under test may. */
  private Baggage failingWork() {
    throw new IllegalStateException("work failed");
  }

  @Test
  @DisplayName("captures every null baggage value, not only the first one in the JVM")
  void capturesEveryNullBaggageValue() throws Exception {
    assertThat(OpenTelemetryUsageIssues.during(this::withTwoNullValues))
        .filteredOn(issue -> issue.contains("value is null"))
        .hasSize(2);
  }

  @Test
  @DisplayName("captures nothing when the work uses the API rightly")
  void capturesNothingForRightUse() throws Exception {
    assertThat(OpenTelemetryUsageIssues.during(Baggage::empty)).isEmpty();
  }

  @Test
  @DisplayName("puts the logger's level back, also when the work fails")
  void putsLevelBack() {
    var levelBefore = log.getLevel();

    assertThatIllegalStateException()
        .isThrownBy(() -> OpenTelemetryUsageIssues.during(this::failingWork));
    assertThat(log.getLevel()).isEqualTo(levelBefore);
  }
}
