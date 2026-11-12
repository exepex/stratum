package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("checkMetadataDefaults keeps the defaults a module's metadata lists true to its code")
class MetadataDefaultChecksTest {

  /** Metadata with a duration, a list and a property without a default. */
  private static final String DEMO_METADATA =
      """
      {"properties": [
        {"name": "stratum.demo.timeout", "defaultValue": "5s"},
        {"name": "stratum.demo.paths", "defaultValue": ["/actuator/**", "/internal/*"]},
        {"name": "stratum.demo.url"}
      ]}
      """;

  /** The values the demo module uses when nothing is set. */
  private static final Map<String, String> DEMO_DEFAULTS =
      Map.of("stratum.demo.timeout", "5s", "stratum.demo.paths", "/actuator/**,/internal/*");

  @TempDir private Path folder;

  @Test
  @DisplayName("passes when each listed default, a list included, is the value the module uses")
  void passesForDefaultsInEffect() throws IOException {
    MetadataDefaultChecks.checkMetadataDefaults(metadata(DEMO_METADATA), this::demoInEffect);
  }

  @Test
  @DisplayName("fails when a listed default is not the value the module uses")
  void failsForDefaultNotInEffect() throws IOException {
    var metadata = metadata(DEMO_METADATA.replace("\"5s\"", "\"6s\""));

    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> MetadataDefaultChecks.checkMetadataDefaults(metadata, this::demoInEffect))
        .withMessageContaining("stratum.demo.timeout=6s");
  }

  @Test
  @DisplayName("fails when the metadata lists no default at all")
  void failsWithoutDefaults() throws IOException {
    var metadata = metadata("{\"properties\": [{\"name\": \"stratum.demo.url\"}]}");

    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(
            () -> MetadataDefaultChecks.checkMetadataDefaults(metadata, this::demoInEffect));
  }

  /** Writes the metadata to a file. */
  private Path metadata(String json) throws IOException {
    return Files.writeString(folder.resolve("spring-configuration-metadata.json"), json);
  }

  /** Returns the values a demo module uses: each set property, else its own default. */
  private List<String> demoInEffect(Map<String, String> properties) {
    return DEMO_DEFAULTS.keySet().stream()
        .sorted()
        .map(key -> Optional.ofNullable(properties.get(key)).orElse(DEMO_DEFAULTS.get(key)))
        .toList();
  }
}
