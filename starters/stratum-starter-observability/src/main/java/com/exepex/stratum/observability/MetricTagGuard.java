package com.exepex.stratum.observability;

import com.exepex.stratum.core.error.ErrorReport;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * Drops every metric with a tag named like an ID, such as {@code userId} or {@code trace_id},
 * because each ID value would make a new time series and can overload the metrics backend. Names
 * are compared ignoring case, {@code _}, {@code -} and {@code .}. Each dropped metric is reported
 * once, with {@code SX-OBSERVABILITY-1002}.
 */
@Slf4j
public class MetricTagGuard implements MeterFilter {

  /** The built-in tag names that hold IDs or personal data, written as they are compared. */
  private static final Set<String> ID_TAG_NAMES =
      Set.of(
          "userid",
          "customerid",
          "accountid",
          "orderid",
          "sessionid",
          "requestid",
          "transactionid",
          "traceid",
          "spanid",
          "correlationid",
          "email",
          "ipaddress");

  /** The built-in names and the service's own, less the allowed ones, written as compared. */
  private final Set<String> forbiddenTagNames;

  /** The names of the metrics already reported, so each is reported once. */
  private final Set<String> reportedMetricNames = ConcurrentHashMap.newKeySet();

  /**
   * Makes a guard that refuses the built-in names and the service's own, except the allowed ones.
   *
   * @param extraForbiddenTagNames the service's own names, from {@code
   *     stratum.observability.tags.extra-forbidden}
   * @param allowedTagNames the names the service keeps, from {@code
   *     stratum.observability.tags.allowed}
   */
  public MetricTagGuard(
      Collection<String> extraForbiddenTagNames, Collection<String> allowedTagNames) {
    var comparableAllowedTagNames =
        allowedTagNames.stream().map(this::comparable).collect(Collectors.toUnmodifiableSet());
    forbiddenTagNames =
        Stream.concat(ID_TAG_NAMES.stream(), extraForbiddenTagNames.stream().map(this::comparable))
            .filter(tagName -> !comparableAllowedTagNames.contains(tagName))
            .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Refuses a metric that has a forbidden tag, and lets every other metric through.
   *
   * @param id the metric's name and tags
   * @return {@code DENY} for a metric with a forbidden tag, otherwise {@code NEUTRAL}
   */
  @Override
  public MeterFilterReply accept(Meter.Id id) {
    var forbiddenTag =
        id.getTags().stream()
            .map(Tag::getKey)
            .filter(tagName -> forbiddenTagNames.contains(comparable(tagName)))
            .findFirst();
    forbiddenTag
        .filter(tagName -> reportedMetricNames.add(id.getName()))
        .ifPresent(tagName -> reportDropped(id.getName(), tagName));
    return forbiddenTag.isPresent() ? MeterFilterReply.DENY : MeterFilterReply.NEUTRAL;
  }

  /** Logs the warning for a dropped metric, naming the metric and the tag but never a value. */
  private void reportDropped(String metricName, String tagName) {
    if (log.isWarnEnabled()) {
      var report =
          ErrorReport.of(
              ObservabilityErrorCode.METRIC_WITH_ID_TAG_DROPPED,
              "metric " + metricName + ", tag " + tagName);
      // The report already turns line breaks into spaces; SpotBugs only sees this replacement.
      log.warn(report.getCodeAndMessage().replaceAll("[\r\n]", " "));
    }
  }

  /** Returns the tag name as it is compared: lower case, without underscores, hyphens and dots. */
  private String comparable(String tagName) {
    return tagName.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(".", "");
  }
}
