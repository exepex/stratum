package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The HTTP client library's jar tells IDEs about every stratum.http-client property")
class ConfigurationMetadataTest {

  /** The metadata IDEs read from the jar, relative to this module's directory. */
  private static final Path METADATA =
      Path.of("target/classes/META-INF/spring-configuration-metadata.json");

  /** The settings of each client, which IDEs also show under defaults. */
  private static final List<String> CLIENT_KEYS =
      List.of(
          "url",
          "connect-timeout",
          "timeout",
          "max-connections",
          "max-wait",
          "idle-timeout",
          "connection-lifetime",
          "shared-pool");

  @Test
  @DisplayName("every property is listed with a description")
  void listsEveryProperty() throws IOException {
    var metadata = Files.readString(METADATA);
    var expected =
        Stream.concat(
                Stream.of(HttpClientConstants.ENABLED, HttpClientConstants.CLIENTS),
                CLIENT_KEYS.stream().map(key -> HttpClientConstants.DEFAULTS + "." + key))
            .toList();

    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].name"))
        .containsExactlyInAnyOrderElementsOf(expected);
    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].description"))
        .hasSameSizeAs(expected)
        .doesNotContainNull();
  }
}
