package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
          "shared-pool",
          "retry.max-retries",
          "retry.backoff-policy",
          "retry.delay",
          "retry.multiplier",
          "retry.max-delay",
          "total-timeout",
          "circuit-breaker.enabled",
          "circuit-breaker.failure-rate-threshold",
          "circuit-breaker.sliding-window-size",
          "circuit-breaker.minimum-number-of-calls",
          "circuit-breaker.wait-duration-in-open-state",
          "circuit-breaker.permitted-number-of-calls-in-half-open-state",
          "circuit-breaker.slow-call-duration-threshold",
          "circuit-breaker.slow-call-rate-threshold",
          "mock",
          "interfaces",
          "call-log",
          "ssl-bundle",
          "auth.api-key",
          "auth.api-key-header",
          "auth.bearer",
          "auth.basic.username",
          "auth.basic.password",
          "auth.client-credentials",
          "auth.token-relay");

  /**
   * The removed keys, with the key that replaces each, which IDEs flag as errors under defaults.
   */
  private static final Map<String, String> REMOVED_KEYS =
      Map.of(
          "retries", "retry.max-retries",
          "retry-delay", "retry.delay",
          "circuit-breaker", "circuit-breaker.enabled");

  @Test
  @DisplayName("every property is listed with a description")
  void listsEveryProperty() throws IOException {
    var metadata = Files.readString(METADATA);
    var expected =
        Stream.of(
                Stream.of(HttpClientConstants.ENABLED, HttpClientConstants.CLIENTS),
                CLIENT_KEYS.stream().map(this::sharedKey),
                REMOVED_KEYS.keySet().stream().map(this::sharedKey))
            .flatMap(Function.identity())
            .toList();

    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].name"))
        .containsExactlyInAnyOrderElementsOf(expected);
    assertThat(JsonPath.<List<String>>read(metadata, "$.properties[*].description"))
        .hasSameSizeAs(expected)
        .doesNotContainNull();
  }

  @Test
  @DisplayName("each removed key is flagged as an error, with the key that replaces it")
  void flagsRemovedKeys() throws IOException {
    var metadata = Files.readString(METADATA);

    REMOVED_KEYS.forEach((removed, replacement) -> assertRemoved(metadata, removed, replacement));
  }

  /** Checks that one removed key is flagged as an error and names its replacement. */
  private void assertRemoved(String metadata, String removed, String replacement) {
    var entry = "$.properties[?(@.name == '" + sharedKey(removed) + "')].deprecation";
    assertThat(JsonPath.<List<String>>read(metadata, entry + ".level")).containsExactly("error");
    assertThat(JsonPath.<List<String>>read(metadata, entry + ".replacement"))
        .containsExactly(sharedKey(replacement));
  }

  /** Returns the key under the shared defaults. */
  private String sharedKey(String key) {
    return HttpClientConstants.DEFAULTS + "." + key;
  }
}
