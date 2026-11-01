package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The HTTP client library's jar tells IDEs about every stratum.http-client property")
class ConfigurationMetadataTest {

  /** The metadata IDEs read from the jar, relative to this module's directory. */
  private static final Path METADATA =
      Path.of("target/classes/META-INF/spring-configuration-metadata.json");

  /** Every property of the module, the switch included. */
  private static final List<String> PROPERTY_NAMES =
      List.of(
          HttpClientConstants.ENABLED,
          HttpClientConstants.POOL_MAX_CONNECTIONS,
          HttpClientConstants.POOL_MAX_WAIT);

  @Test
  @DisplayName("every property is listed with a description and a default")
  void listsEveryProperty() throws IOException {
    var metadata = Files.readString(METADATA);

    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].name"))
        .containsExactlyInAnyOrderElementsOf(PROPERTY_NAMES);
    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].description"))
        .hasSameSizeAs(PROPERTY_NAMES)
        .doesNotContainNull();
    assertThat(JsonPath.<List<Object>>read(metadata, "$.properties[*].defaultValue"))
        .hasSameSizeAs(PROPERTY_NAMES)
        .doesNotContainNull();
  }
}
