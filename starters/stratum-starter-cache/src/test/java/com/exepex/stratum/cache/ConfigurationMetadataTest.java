package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The cache starter's jar tells IDEs about every stratum.cache property")
class ConfigurationMetadataTest {

  /** The metadata IDEs read from the jar, relative to this module's directory. */
  private static final Path METADATA =
      Path.of("target/classes/META-INF/spring-configuration-metadata.json");

  @Test
  @DisplayName("the module switch is listed with a description")
  void listsEveryProperty() throws IOException {
    var metadata = Files.readString(METADATA);

    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].name"))
        .containsExactly(CacheConstants.ENABLED);
    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].description"))
        .hasSize(1)
        .doesNotContainNull();
  }
}
