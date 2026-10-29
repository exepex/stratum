package com.exepex.stratum.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The logging starter's jar tells IDEs about every stratum.logging property")
class ConfigurationMetadataTest {

  /** The metadata IDEs read from the jar, relative to this module's directory. */
  private static final Path METADATA =
      Path.of("target/classes/META-INF/spring-configuration-metadata.json");

  private static final String STACK_TRACE_ROOT = LoggingConstants.PREFIX + ".stack-trace-root";

  /** Every property of the module, the switch included. */
  private static final List<String> PROPERTY_NAMES =
      List.of(
          LoggingConstants.ENABLED,
          LoggingConstants.FORMAT,
          LoggingConstants.PREFIX + ".max-field-length",
          STACK_TRACE_ROOT,
          LoggingConstants.PREFIX + ".redaction.extra-keys",
          LoggingConstants.EXTRA_PATTERNS);

  @Test
  @DisplayName("every property has a description, and the choices of each enum are offered")
  void listsEveryProperty() throws IOException {
    var metadata = Files.readString(METADATA);

    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].name"))
        .containsExactlyInAnyOrderElementsOf(PROPERTY_NAMES);
    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].description"))
        .hasSameSizeAs(PROPERTY_NAMES)
        .doesNotContainNull();
    assertThat(JsonPath.<List<String>>read(metadata, "$.hints[*].name"))
        .containsExactly(LoggingConstants.FORMAT, STACK_TRACE_ROOT);
  }
}
