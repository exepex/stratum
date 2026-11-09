package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("TelemetryUrlFilter keeps passwords and unbounded values out of spans and metrics")
class TelemetryUrlFilterTest {

  /** What Spring records when a call has no URL or URI template. */
  private static final String NONE = "none";

  /** URLs as Spring records them, each with the URL that must be kept: only the user info goes. */
  private static final List<Arguments> URLS_AND_CLEANED_URLS =
      List.of(
          Arguments.of(
              "https://partner.com/rates?currency=EUR&page=2",
              "https://partner.com/rates?currency=EUR&page=2"),
          Arguments.of("https://user:secret@partner.com/rates", "https://partner.com/rates"),
          Arguments.of(
              "https://user:secret@partner.com:8443/rates?currency=EUR#top",
              "https://partner.com:8443/rates?currency=EUR#top"),
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
    return recordedUrl(filter, url);
  }

  /**
   * Returns the URL recorded after the given filter ran on a context holding {@code url}.
   *
   * @param urlFilter the filter to run
   * @param url the URL as Spring records it
   * @return the URL that is kept
   */
  private String recordedUrl(TelemetryUrlFilter urlFilter, String url) {
    var context = new Observation.Context();
    context.addHighCardinalityKeyValue(KeyValue.of(ObservabilityConstants.HTTP_URL, url));
    return urlFilter
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
  @DisplayName("the span keeps the URL, ordinary query values included, without its user info")
  void removesUserInfo(String url, String expected) {
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
    assertThat(recordedUrl("https://partner.com:port/rates?currency=EUR"))
        .isEqualTo(TelemetryUrlFilter.UNPARSABLE_URL);
  }

  @Nested
  @DisplayName("query values on the span are masked like log lines")
  class QueryMasking {

    /**
     * URLs whose query holds a secret or personal data, each with the URL that must be kept: the
     * host, the path and the ordinary values stay, as in a log line.
     */
    private static final List<Arguments> URLS_AND_MASKED_URLS =
        List.of(
            Arguments.of(
                "https://partner.com/rates?currency=EUR&api_key=abc123",
                "https://partner.com/rates?currency=EUR&api_key=****"),
            Arguments.of(
                "https://partner.com/me?access_token=abc123&page=2",
                "https://partner.com/me?access_token=****&page=2"),
            Arguments.of(
                "https://partner.com/pay?card=4111111111111111",
                "https://partner.com/pay?card=****1111"),
            Arguments.of(
                "https://partner.com/pay?ref=4111111111111111",
                "https://partner.com/pay?ref=****1111"),
            Arguments.of(
                "https://partner.com/users?to=jane.doe@example.com",
                "https://partner.com/users?to=****.com"),
            Arguments.of(
                "https://user:secret@partner.com/rates#password=abc123",
                "https://partner.com/rates#password=****"));

    /** URLs whose host or path holds a sensitive-looking word, each kept as it is. */
    private static final List<String> URLS_KEPT_AS_THEY_ARE =
        List.of(
            "https://token.partner.com/password/4111111111111111",
            "https://partner.com/users/jane.doe@example.com/orders");

    @ParameterizedTest
    @FieldSource("URLS_AND_MASKED_URLS")
    @DisplayName("a secret or personal data in the query or fragment is masked")
    void masksQueryValues(String url, String expected) {
      assertThat(recordedUrl(url)).isEqualTo(expected);
    }

    @ParameterizedTest
    @FieldSource("URLS_KEPT_AS_THEY_ARE")
    @DisplayName("the host and path are never masked")
    void keepsHostAndPath(String url) {
      assertThat(recordedUrl(url)).isEqualTo(url);
    }
  }

  @Nested
  @DisplayName("a URL longer than a span keeps")
  class LongUrls {

    /** The longest URL kept in these tests, in characters. */
    private static final int SHORT_LIMIT = 42;

    /** URLs longer than the limit, or just at it, each with the URL that must be kept. */
    private static final List<Arguments> LONG_URLS_AND_KEPT_URLS =
        List.of(
            Arguments.of(
                "https://partner.com/rates?currency=EUR&p=1",
                "https://partner.com/rates?currency=EUR&p=1"),
            Arguments.of(
                "https://partner.com/rates?currency=EUR&pg=1",
                "https://partner.com/rates?currency=EUR"),
            Arguments.of(
                "https://partner.com/rates?currency=EUR&card=4111111111111111",
                "https://partner.com/rates?currency=EUR"),
            Arguments.of(
                "https://partner.com/r?ref=4111111111111111111111", "https://partner.com/r"),
            Arguments.of(
                "https://partner.com/" + "a".repeat(30) + "?token=abc",
                "https://partner.com/" + "a".repeat(30)));

    private final TelemetryUrlFilter shortFilter = new TelemetryUrlFilter(SHORT_LIMIT);

    @ParameterizedTest
    @FieldSource("LONG_URLS_AND_KEPT_URLS")
    @DisplayName("keeps the query's whole parameters that fit, and never part of a value")
    void keepsWholeParametersThatFit(String url, String expected) {
      assertThat(recordedUrl(shortFilter, url)).isEqualTo(expected);
    }
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
