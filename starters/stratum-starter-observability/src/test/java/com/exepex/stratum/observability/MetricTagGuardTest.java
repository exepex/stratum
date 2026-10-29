package com.exepex.stratum.observability;

import static com.exepex.stratum.observability.testsupport.MetricTestData.METRIC_NAME;
import static com.exepex.stratum.observability.testsupport.MetricTestData.TAG_VALUE;
import static com.exepex.stratum.observability.testsupport.MetricTestData.counterTaggedWith;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
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
      List.of("userId", "user_id", "USER-ID", "user.id", "trace_id", "correlationId");

  /** A service's own tag name, added to the built-in ones. */
  private static final String TENANT_TAG = "tenantId";

  /** A built-in name the service keeps, spelled differently from the built-in one. */
  private static final String ALLOWED_ORDER_TAG = "order_id";

  private final MetricTagGuard guard =
      new MetricTagGuard(List.of(TENANT_TAG), List.of(ALLOWED_ORDER_TAG));

  /** Returns the reply for a counter with one tag. */
  private MeterFilterReply replyForTag(String tagName) {
    return guard.accept(counterTaggedWith(tagName));
  }

  @ParameterizedTest
  @FieldSource("ID_TAG_SPELLINGS")
  @DisplayName("a built-in ID name is refused whatever its case, _, - or .")
  void refusesIdTag(String tagName) {
    assertThat(replyForTag(tagName)).isEqualTo(MeterFilterReply.DENY);
  }

  @Test
  @DisplayName("the service's own name is refused as well")
  void refusesServiceTag() {
    assertThat(replyForTag("tenant_id")).isEqualTo(MeterFilterReply.DENY);
  }

  @Test
  @DisplayName("a built-in name the service allows is kept, whatever its spelling")
  void keepsAllowedTag() {
    assertThat(replyForTag("orderId")).isEqualTo(MeterFilterReply.NEUTRAL);
  }

  @Test
  @DisplayName("a metric with bounded tags, or none, is kept")
  void keepsBoundedTags() {
    assertThat(replyForTag("uri")).isEqualTo(MeterFilterReply.NEUTRAL);
    assertThat(guard.accept(new Meter.Id(METRIC_NAME, Tags.empty(), null, null, Meter.Type.TIMER)))
        .isEqualTo(MeterFilterReply.NEUTRAL);
  }

  @Test
  @DisplayName("a RabbitMQ routing key is removed from a metric, which is kept with its other tags")
  void removesRoutingKey() {
    var rabbitSend =
        new Meter.Id(
            "spring.rabbit.template",
            Tags.of(
                ObservabilityConstants.RABBIT_ROUTING_KEY_TAG,
                "order.42.created",
                "messaging.destination.name",
                "orders"),
            null,
            null,
            Meter.Type.TIMER);

    var kept = guard.map(rabbitSend);

    assertThat(kept.getTags()).containsExactly(Tag.of("messaging.destination.name", "orders"));
    assertThat(guard.accept(kept)).isEqualTo(MeterFilterReply.NEUTRAL);
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
