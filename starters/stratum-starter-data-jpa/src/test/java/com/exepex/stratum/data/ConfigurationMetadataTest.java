package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The data starter's jar tells IDEs about every stratum.data property")
class ConfigurationMetadataTest {

  /** The metadata IDEs read from the jar, relative to this module's directory. */
  private static final Path METADATA =
      Path.of("target/classes/META-INF/spring-configuration-metadata.json");

  @Test
  @DisplayName("the module switch and the statement time limit are listed with a description")
  void listsEveryProperty() throws IOException {
    var metadata = Files.readString(METADATA);

    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].name"))
        .containsExactlyInAnyOrder(DataConstants.ENABLED, DataConstants.QUERY_TIMEOUT);
    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].description"))
        .hasSize(2)
        .doesNotContainNull();
  }
}
