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
  @DisplayName("every shared property is listed with a type and a description")
  void listsSharedProperties() throws IOException {
    var metadata = Files.readString(METADATA);

    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].name"))
        .containsExactly(
            "stratum.environment",
            "stratum.context-propagation.async-threads",
            "stratum.context-propagation.reactor",
            "stratum.context-propagation.executor-beans");
    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].type"))
        .containsExactly(
            "java.lang.String", "java.lang.Boolean", "java.lang.Boolean", "java.lang.Boolean");
    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].description"))
        .doesNotContainNull()
        .hasSameSizeAs(JsonPath.<List<String>>read(metadata, "$.properties[*].name"));
    assertThat(JsonPath.<List<Boolean>>read(metadata, "$.properties[1:].defaultValue"))
        .containsExactly(true, true, true);
  }
}
