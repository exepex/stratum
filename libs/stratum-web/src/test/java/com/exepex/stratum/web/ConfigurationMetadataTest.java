package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The web library's jar tells IDEs about every stratum.web property")
class ConfigurationMetadataTest {

  /** The metadata IDEs read from the jar, relative to this module's directory. */
  private static final Path METADATA =
      Path.of("target/classes/META-INF/spring-configuration-metadata.json");

  /** Every property of the module, the switch included. */
  private static final List<String> PROPERTY_NAMES =
      List.of(
          WebConstants.ENABLED,
          WebConstants.ERROR_STATUSES,
          WebConstants.CORRELATION_ACCEPT_FROM_CALLER,
          WebConstants.CLIENT_IP_TRUSTED_PROXIES,
          WebConstants.USER_AGENT_PARSE,
          WebConstants.USER_AGENT_CACHE_SIZE,
          WebConstants.USER_AGENT_REQUEST_CLIENT_HINTS,
          WebConstants.CLIENT_LOCATION_DATABASE,
          WebConstants.REQUEST_FIELDS_MAX_VALUE_LENGTH,
          WebConstants.REQUEST_LOG_ENABLED,
          WebConstants.REQUEST_LOG_SKIP_PATHS,
          WebConstants.REQUEST_LOG_REQUEST_HEADERS,
          WebConstants.REQUEST_LOG_RESPONSE_HEADERS,
          WebConstants.REQUEST_LOG_DENIED_HEADERS,
          WebConstants.REQUEST_LOG_TOKEN_PAYLOAD,
          WebConstants.REQUEST_LOG_TOKEN_CLAIMS);

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
