package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("TelemetryUrlFilter keeps tokens and passwords in URLs out of spans and metrics")
class TelemetryUrlFilterTest {

  /** What Spring records when a call has no URL or URI template. */
  private static final String NONE = "none";

  /** URLs as Spring records them, each with the URL that must be kept. */
  private static final List<Arguments> URLS_AND_CLEANED_URLS =
      List.of(
          Arguments.of("https://partner.com/rates?api_key=abc123", "https://partner.com/rates"),
          Arguments.of("https://user:secret@partner.com/rates", "https://partner.com/rates"),
          Arguments.of(
              "https://user:secret@partner.com:8443/rates?token=abc#top",
              "https://partner.com:8443/rates"),
          Arguments.of("https://partner.com?token=abc", "https://partner.com"),
          Arguments.of("https://partner.com/rates?email=a@b.com", "https://partner.com/rates"),
          Arguments.of(
              "http://inventory_service:8080/items/42", "http://inventory_service:8080/items/42"),
          Arguments.of(NONE, NONE));

  /** URI templates as Spring records them, each with the template that must be kept. */
  private static final List<Arguments> URI_TEMPLATES_AND_CLEANED_TEMPLATES =
      List.of(
          Arguments.of("/rates?api_key=abc123", "/rates"),
          Arguments.of("/rates#top", "/rates"),
          Arguments.of("/orders/{id}?token={token}", "/orders/{id}"),
          Arguments.of("/orders/{id}", "/orders/{id}"),
          Arguments.of(NONE, NONE));

  private final TelemetryUrlFilter filter = new TelemetryUrlFilter();

  /**
   * Returns the URL recorded after the filter ran on a context holding {@code url}.
   *
   * @param url the URL as Spring records it
   * @return the URL that is kept
   */
  private String recordedUrl(String url) {
    var context = new Observation.Context();
    context.addHighCardinalityKeyValue(KeyValue.of(ObservabilityConstants.HTTP_URL, url));
    return filter
        .map(context)
        .getHighCardinalityKeyValue(ObservabilityConstants.HTTP_URL)
        .getValue();
  }

  /**
   * Returns the URI template recorded after the filter ran on a context holding {@code template}.
   *
   * @param template the URI template as Spring records it
   * @return the URI template that is kept
   */
  private String recordedUriTemplate(String template) {
    var context = new Observation.Context();
    context.addLowCardinalityKeyValue(KeyValue.of(ObservabilityConstants.URI, template));
    return filter.map(context).getLowCardinalityKeyValue(ObservabilityConstants.URI).getValue();
  }

  @ParameterizedTest
  @FieldSource("URLS_AND_CLEANED_URLS")
  @DisplayName("the query string, fragment and user info are removed; the rest is kept")
  void removesQueryAndUserInfo(String url, String expected) {
    assertThat(recordedUrl(url)).isEqualTo(expected);
  }

  @ParameterizedTest
  @FieldSource("URI_TEMPLATES_AND_CLEANED_TEMPLATES")
  @DisplayName("the uri tag, on spans and metrics, loses its query string and fragment")
  void removesQueryFromUriTag(String template, String expected) {
    assertThat(recordedUriTemplate(template)).isEqualTo(expected);
  }

  @Test
  @DisplayName("a metric's uri tag loses its query string; its other tags are kept")
  void removesQueryFromMetricUriTag() {
    var registry = new SimpleMeterRegistry();
    registry.config().meterFilter(filter);

    var id =
        registry
            .counter("http.client.requests.active", ObservabilityConstants.URI, "/rates?key=abc")
            .getId();

    assertThat(id.getTag(ObservabilityConstants.URI)).isEqualTo("/rates");
    assertThat(registry.counter("orders.placed", "region", "eu").getId().getTag("region"))
        .isEqualTo("eu");
  }

  @Test
  @DisplayName("a URL that cannot be parsed is replaced by a placeholder")
  void replacesUnparsableUrl() {
    assertThat(recordedUrl("https://partner.com:port/rates?token=abc"))
        .isEqualTo(TelemetryUrlFilter.UNPARSABLE_URL);
  }

  @Test
  @DisplayName("an observation without a URL is left as it is")
  void leavesOtherObservations() {
    var context = new Observation.Context();
    context.addHighCardinalityKeyValue(KeyValue.of("order.id", "42"));

    assertThat(filter.map(context).getHighCardinalityKeyValues())
        .containsExactly(KeyValue.of("order.id", "42"));
  }
}
