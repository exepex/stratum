package com.exepex.stratum.observability.testsupport;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import lombok.experimental.UtilityClass;

/** Sample metrics for the tag guard's tests. */
@UtilityClass
public class MetricTestData {

  /** The name of every sample metric. */
  public static final String METRIC_NAME = "orders.placed";

  /** The value of the sample tag, made up, which the guard must never report. */
  public static final String TAG_VALUE = "user-7731";

  /**
   * Returns a counter's name and tags, with one tag.
   *
   * @param tagName the tag's name; its value is {@link #TAG_VALUE}
   * @return the counter's ID, as a meter filter sees it
   */
  public static Meter.Id counterTaggedWith(String tagName) {
    return new Meter.Id(METRIC_NAME, Tags.of(tagName, TAG_VALUE), null, null, Meter.Type.COUNTER);
  }
}
