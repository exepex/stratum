package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The shared data library's jar tells IDEs about every stratum.data property")
class ConfigurationMetadataTest {

  /** The metadata IDEs read from the jar, relative to this module's directory. */
  private static final Path METADATA =
      Path.of("target/classes/META-INF/spring-configuration-metadata.json");

  @Test
  @DisplayName(
      "the module switch, the time limit, the margin, the named pools and the cap are listed with a"
          + " description")
  void listsEveryProperty() throws IOException {
    var metadata = Files.readString(METADATA);

    var properties =
        List.of(
            DataConstants.ENABLED,
            DataConstants.QUERY_TIMEOUT,
            DataConstants.DEADLINE_MARGIN,
            DataConstants.SOURCES,
            DataConstants.MAX_CONNECTIONS);
    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].name"))
        .containsExactlyInAnyOrderElementsOf(properties);
    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].description"))
        .hasSameSizeAs(properties)
        .doesNotContainNull();
  }
}
