package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("SpanUrlFilter keeps tokens and passwords in URLs out of spans")
class SpanUrlFilterTest {

  private final SpanUrlFilter filter = new SpanUrlFilter();

  /** Returns the URL recorded after the filter ran on a context holding {@code url}. */
  private String recordedUrl(String url) {
    var context = new Observation.Context();
    context.addHighCardinalityKeyValue(KeyValue.of(ObservabilityConstants.HTTP_URL, url));
    return filter
        .map(context)
        .getHighCardinalityKeyValue(ObservabilityConstants.HTTP_URL)
        .getValue();
  }

  @ParameterizedTest
  @CsvSource({
    "https://partner.com/rates?api_key=abc123, https://partner.com/rates",
    "https://user:secret@partner.com/rates, https://partner.com/rates",
    "https://user:secret@partner.com:8443/rates?token=abc#top, https://partner.com:8443/rates",
    "https://partner.com?token=abc, https://partner.com",
    "https://partner.com/rates?email=a@b.com, https://partner.com/rates",
    "http://inventory_service:8080/items/42, http://inventory_service:8080/items/42",
    "none, none"
  })
  @DisplayName("the query string, fragment and user info are removed; the rest is kept")
  void removesQueryAndUserInfo(String url, String expected) {
    assertThat(recordedUrl(url)).isEqualTo(expected);
  }

  @Test
  @DisplayName("a URL that cannot be parsed is replaced by a placeholder")
  void replacesUnparsableUrl() {
    assertThat(recordedUrl("https://partner.com:port/rates?token=abc"))
        .isEqualTo(SpanUrlFilter.UNPARSABLE_URL);
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
