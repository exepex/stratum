package com.exepex.stratum.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("stratum-core's jar tells IDEs about the properties every module shares")
class ConfigurationMetadataTest {

  /** The metadata IDEs read from the jar, relative to this module's directory. */
  private static final Path METADATA =
      Path.of("target/classes/META-INF/spring-configuration-metadata.json");

  @Test
  @DisplayName("stratum.environment and stratum.propagate-context are listed with a description")
  void listsSharedProperties() throws IOException {
    var metadata = Files.readString(METADATA);

    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].name"))
        .containsExactly("stratum.environment", "stratum.propagate-context");
    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].type"))
        .containsExactly("java.lang.String", "java.lang.Boolean");
    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].description"))
        .doesNotContainNull()
        .hasSize(2);
    assertThat(JsonPath.<Boolean>read(metadata, "$.properties[1].defaultValue")).isTrue();
  }
}
