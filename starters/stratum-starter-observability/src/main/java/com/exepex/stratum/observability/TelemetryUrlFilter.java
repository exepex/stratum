package com.exepex.stratum.observability;

import com.exepex.stratum.redaction.ContentMaskingRules;
import com.exepex.stratum.redaction.SensitiveFieldNames;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Keeps passwords, secrets, personal data and unbounded values out of the URLs that Spring records
 * for an outgoing HTTP call. On the span's URL it removes the user info and masks the query with
 * the logging starter's built-in rules, so {@code
 * https://user:secret@partner.com/rates?currency=EUR&api_key=abc} is recorded as {@code
 * https://partner.com/rates?currency=EUR&api_key=****}; the host and path are kept as they are. The
 * service's own {@code extra-keys} and {@code extra-patterns} mask its logs only. The {@code uri}
 * tag, on the span and on every metric, loses its query, {@code /rates}, because each distinct
 * value there would be a new time series. It is a {@link MeterFilter} as well as an {@link
 * ObservationFilter}, because Spring creates the {@code http.client.requests.active} metric when a
 * call starts, before an observation filter runs. When a call fails, Spring's exception message
 * names the URL with its user info; Stratum keeps that message off the span when it is sent.
 */
@RequiredArgsConstructor
public class TelemetryUrlFilter implements ObservationFilter, MeterFilter {

  /** Recorded in place of a URL that cannot be parsed. */
  static final String UNPARSABLE_URL = "[UNPARSABLE_URL]";

  /** The start of a query string or fragment, compiled once because it runs on every call. */
  private static final Pattern QUERY_OR_FRAGMENT = Pattern.compile("[?#]");

  /** The built-in masking rules, built once because they run on every call. */
  private final UnaryOperator<String> maskingRules =
      ContentMaskingRules.builtInRules(new SensitiveFieldNames(List.of()));

  /**
   * The longest a span keeps the URL, Spring Boot's {@code max-attribute-value-length}; the query
   * beyond it is never masked, because the span would not keep it.
   */
  private final int maxUrlLength;

  /**
   * Makes a filter that keeps as much of a URL as Stratum's default span limit, 1,024 characters.
   */
  public TelemetryUrlFilter() {
    this(ObservabilityConstants.DEFAULT_MAX_ATTRIBUTE_VALUE_LENGTH);
  }

  /**
   * Removes the user info from the recorded URL and masks its query, and removes the query from the
   * URI template, if the observation has them.
   *
   * @param context the observation's data, before it becomes a span and a metric
   * @return the same context
   */
  @Override
  public Observation.Context map(Observation.Context context) {
    var url = context.getHighCardinalityKeyValue(ObservabilityConstants.HTTP_URL);
    if (url != null) {
      context.addHighCardinalityKeyValue(
          KeyValue.of(
              ObservabilityConstants.HTTP_URL, withMaskedQuery(withoutUserInfo(url.getValue()))));
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
    return uriTemplate.substring(0, queryStart(uriTemplate));
  }

  /**
   * Returns the URL with its query string and fragment masked; the scheme, host and path stay as
   * they are.
   */
  private String withMaskedQuery(String url) {
    var queryStart = queryStart(url);
    return url.substring(0, queryStart) + maskingRules.apply(keptQuery(url, queryStart));
  }

  /**
   * Returns the query and fragment up to the last whole parameter that fits in the span's limit, so
   * that a long hostile query costs no more to mask than the span keeps. A parameter the limit
   * would cut is left out whole, so no part of its value, such as a card number's first digits, is
   * kept unmasked.
   */
  private String keptQuery(String url, int queryStart) {
    if (url.length() <= maxUrlLength) {
      return url.substring(queryStart);
    }
    var lastWholeParameterEnd =
        IntStream.of(url.lastIndexOf('&', maxUrlLength), url.lastIndexOf('#', maxUrlLength))
            .max()
            .orElseThrow();
    return lastWholeParameterEnd > queryStart
        ? url.substring(queryStart, lastWholeParameterEnd)
        : "";
  }

  /** Returns where the query string or fragment starts, or the length when there is neither. */
  private int queryStart(String urlOrPath) {
    var queryOrFragment = QUERY_OR_FRAGMENT.matcher(urlOrPath);
    return queryOrFragment.find() ? queryOrFragment.start() : urlOrPath.length();
  }

  /**
   * Returns the URL without its user info, or a placeholder if it cannot be parsed, so that a URL
   * that could not be cleaned is never recorded.
   */
  private String withoutUserInfo(String url) {
    try {
      return UriComponentsBuilder.fromUriString(url).userInfo(null).build().toUriString();
    } catch (IllegalArgumentException unparsableUrl) {
      return UNPARSABLE_URL;
    }
  }
}
