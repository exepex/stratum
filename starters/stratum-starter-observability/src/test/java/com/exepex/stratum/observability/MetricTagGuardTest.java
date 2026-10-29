package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilterReply;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@DisplayName("MetricTagGuard drops metrics tagged with an ID")
@ExtendWith(OutputCaptureExtension.class)
class MetricTagGuardTest {

  /** Spellings of a built-in ID name, which all match it. */
  private static final List<String> ID_TAG_SPELLINGS =
      List.of("userId", "user_id", "USER-ID", "trace_id", "correlationId");

  /** A service's own tag name, added to the built-in ones. */
  private static final String TENANT_TAG = "tenantId";

  /** The value of the tag, which is never reported. */
  private static final String TAG_VALUE = "user-7731";

  /** The name of the metric in each test. */
  private static final String METRIC_NAME = "orders.placed";

  private final MetricTagGuard guard = new MetricTagGuard(List.of(TENANT_TAG));

  /** Returns the reply for a counter with one tag. */
  private MeterFilterReply replyForTag(String tagName) {
    return guard.accept(
        new Meter.Id(METRIC_NAME, Tags.of(tagName, TAG_VALUE), null, null, Meter.Type.COUNTER));
  }

  @ParameterizedTest
  @FieldSource("ID_TAG_SPELLINGS")
  @DisplayName("a built-in ID name is refused whatever its case, _ or -")
  void refusesIdTag(String tagName) {
    assertThat(replyForTag(tagName)).isEqualTo(MeterFilterReply.DENY);
  }

  @Test
  @DisplayName("the service's own name is refused as well")
  void refusesServiceTag() {
    assertThat(replyForTag("tenant_id")).isEqualTo(MeterFilterReply.DENY);
  }

  @Test
  @DisplayName("a metric with bounded tags, or none, is kept")
  void keepsBoundedTags() {
    assertThat(replyForTag("uri")).isEqualTo(MeterFilterReply.NEUTRAL);
    assertThat(guard.accept(new Meter.Id(METRIC_NAME, Tags.empty(), null, null, Meter.Type.TIMER)))
        .isEqualTo(MeterFilterReply.NEUTRAL);
  }

  @Test
  @DisplayName("a dropped metric is reported once, by name and tag, never with a value")
  void reportsOnce(CapturedOutput output) {
    replyForTag("userId");
    replyForTag("userId");

    assertThat(output.getOut())
        .containsOnlyOnce("SX-OBSERVABILITY-1002")
        .contains("metric orders.placed, tag userId")
        .doesNotContain(TAG_VALUE);
  }
}
