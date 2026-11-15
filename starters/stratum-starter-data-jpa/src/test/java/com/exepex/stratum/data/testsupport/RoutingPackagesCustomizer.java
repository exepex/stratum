package com.exepex.stratum.data.testsupport;

import com.exepex.stratum.data.DataProperties;
import com.exepex.stratum.data.DataPropertiesCustomizer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Replaces the packages of every pool with {@link #JAVA_PACKAGES} in Java when the query timeout is
 * {@link #MARKER}, which no other test sets, so the tests of other settings never see it. Listed in
 * this module's test {@code META-INF/spring.factories}, as a service lists its own.
 */
public class RoutingPackagesCustomizer implements DataPropertiesCustomizer {

  /** The query timeout that asks for the Java packages. */
  public static final Duration MARKER = Duration.ofMillis(4322);

  /** The packages the Java settings give every pool: an entry that is no package name. */
  public static final List<String> JAVA_PACKAGES = List.of("com.*");

  @Override
  public DataProperties customize(DataProperties settings) {
    return MARKER.equals(settings.queryTimeout())
        ? settings.toBuilder()
            .sources(
                settings.sources().entrySet().stream()
                    .collect(
                        Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().toBuilder().packages(JAVA_PACKAGES).build())))
            .build()
        : settings;
  }
}
