package com.exepex.stratum.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Removes the query string and user info from the URL that Spring records on the span of an
 * outgoing HTTP call, so that a token or password in a URL never reaches the trace backend. For
 * example, {@code https://user:secret@partner.com/rates?api_key=abc} is recorded as {@code
 * https://partner.com/rates}.
 */
public class SpanUrlFilter implements ObservationFilter {

  /** Recorded in place of a URL that cannot be parsed. */
  static final String UNPARSABLE_URL = "[UNPARSABLE_URL]";

  /**
   * Replaces the recorded URL, if the observation has one, with the URL without its query string
   * and user info.
   *
   * @param context the observation's data, before it becomes a span
   * @return the same context
   */
  @Override
  public Observation.Context map(Observation.Context context) {
    var url = context.getHighCardinalityKeyValue(ObservabilityConstants.HTTP_URL);
    if (url != null) {
      context.addHighCardinalityKeyValue(
          KeyValue.of(ObservabilityConstants.HTTP_URL, withoutQueryAndUserInfo(url.getValue())));
    }
    return context;
  }

  /**
   * Returns the URL without its user info, query string and fragment, or a placeholder if it cannot
   * be parsed, so that a URL that could not be cleaned is never recorded.
   */
  private String withoutQueryAndUserInfo(String url) {
    try {
      return UriComponentsBuilder.fromUriString(url)
          .userInfo(null)
          .replaceQuery(null)
          .fragment(null)
          .build()
          .toUriString();
    } catch (IllegalArgumentException unparsableUrl) {
      return UNPARSABLE_URL;
    }
  }
}
