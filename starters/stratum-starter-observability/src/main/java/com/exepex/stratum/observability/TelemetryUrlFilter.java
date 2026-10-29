package com.exepex.stratum.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.regex.Pattern;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Removes the query string and user info from the URLs that Spring records for an outgoing HTTP
 * call, so that a token or password in a URL does not reach the trace or metrics backend. For
 * example, {@code https://user:secret@partner.com/rates?api_key=abc} is recorded as {@code
 * https://partner.com/rates} on the span, and its {@code uri} tag, on the span and on every metric,
 * as {@code /rates}. It is a {@link MeterFilter} as well as an {@link ObservationFilter}, because
 * Spring creates the {@code http.client.requests.active} metric when a call starts, before an
 * observation filter runs. It cannot clean the message of an exception: when a call fails, Spring's
 * message names the URL with its user info, and the span records that message.
 */
public class TelemetryUrlFilter implements ObservationFilter, MeterFilter {

  /** Recorded in place of a URL that cannot be parsed. */
  static final String UNPARSABLE_URL = "[UNPARSABLE_URL]";

  /** The start of a query string or fragment, compiled once because it runs on every call. */
  private static final Pattern QUERY_OR_FRAGMENT = Pattern.compile("[?#]");

  /**
   * Replaces the recorded URL and URI template, if the observation has them, with the same without
   * their query string and user info.
   *
   * @param context the observation's data, before it becomes a span and a metric
   * @return the same context
   */
  @Override
  public Observation.Context map(Observation.Context context) {
    var url = context.getHighCardinalityKeyValue(ObservabilityConstants.HTTP_URL);
    if (url != null) {
      context.addHighCardinalityKeyValue(
          KeyValue.of(ObservabilityConstants.HTTP_URL, withoutQueryAndUserInfo(url.getValue())));
    }
    var uriTemplate = context.getLowCardinalityKeyValue(ObservabilityConstants.URI);
    if (uriTemplate != null) {
      context.addLowCardinalityKeyValue(
          KeyValue.of(ObservabilityConstants.URI, withoutQuery(uriTemplate.getValue())));
    }
    return context;
  }

  /**
   * Removes the query string and fragment from a metric's {@code uri} tag, if it has one.
   *
   * @param id the metric's name and tags
   * @return the same name and tags, with the {@code uri} tag cleaned
   */
  @Override
  public Meter.Id map(Meter.Id id) {
    var uriTemplate = id.getTag(ObservabilityConstants.URI);
    if (uriTemplate == null) {
      return id;
    }
    var cleanedUriTemplate = withoutQuery(uriTemplate);
    return cleanedUriTemplate.equals(uriTemplate)
        ? id
        : id.replaceTags(
            Tags.of(id.getTagsAsIterable()).and(ObservabilityConstants.URI, cleanedUriTemplate));
  }

  /**
   * Returns the URI template up to its query string or fragment. Spring has already removed the
   * scheme, host and user info, so the template is a path, such as {@code /rates?api_key=abc}.
   */
  private String withoutQuery(String uriTemplate) {
    var queryOrFragment = QUERY_OR_FRAGMENT.matcher(uriTemplate);
    return queryOrFragment.find() ? uriTemplate.substring(0, queryOrFragment.start()) : uriTemplate;
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
