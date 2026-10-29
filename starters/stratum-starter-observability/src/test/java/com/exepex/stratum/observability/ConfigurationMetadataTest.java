package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName(
    "The observability starter's jar tells IDEs about every stratum.observability property")
class ConfigurationMetadataTest {

  /** The metadata IDEs read from the jar, relative to this module's directory. */
  private static final Path METADATA =
      Path.of("target/classes/META-INF/spring-configuration-metadata.json");

  /** Every property of the module, the switch included. */
  private static final List<String> PROPERTY_NAMES =
      List.of(
          ObservabilityConstants.ENABLED,
          ObservabilityConstants.PREFIX + ".exposed-endpoints",
          ObservabilityConstants.PREFIX + ".histograms",
          ObservabilityConstants.PREFIX + ".messaging-traces",
          ObservabilityConstants.PREFIX + ".tags.extra-forbidden",
          ObservabilityConstants.PREFIX + ".tags.allowed");

  @Test
  @DisplayName("every property is listed with a description")
  void listsEveryProperty() throws IOException {
    var metadata = Files.readString(METADATA);

    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].name"))
        .containsExactlyInAnyOrderElementsOf(PROPERTY_NAMES);
    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].description"))
        .hasSameSizeAs(PROPERTY_NAMES)
        .doesNotContainNull();
  }
}
