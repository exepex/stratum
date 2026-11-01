package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.http.client.autoconfigure.HttpClientProperties;

@DisplayName(
    "Every Spring Boot key the HTTP client page documents still exists and is not deprecated")
class DocumentedSpringKeysTest {

  /** The module page and the starters' READMEs, relative to this module's directory. */
  private static final List<Path> PAGES =
      List.of(
          Path.of("../../docs/modules/http-client.md"),
          Path.of("../../starters/stratum-starter-http-client/README.md"),
          Path.of("../../starters/stratum-starter-reactive-http-client/README.md"));

  /** A Spring Boot HTTP client key as the page writes it. */
  private static final Pattern SPRING_KEY = Pattern.compile("spring\\.http\\.[a-z][\\w.\\-]*\\w");

  /** The prefix of the keys of one named client, which Spring Boot's metadata cannot list. */
  private static final String SERVICE_CLIENT_PREFIX = "spring.http.serviceclient.";

  /** The client name the page's examples use. */
  private static final String CLIENT_NAME = "inventory";

  /** The metadata files of every jar on the test classpath. */
  private static final String METADATA = "META-INF/spring-configuration-metadata.json";

  /** The lists of a metadata file that hold keys: its properties and its groups. */
  private static final List<String> METADATA_LISTS = List.of("properties", "groups");

  /** Returns every Spring Boot HTTP client key on the pages, each once. */
  private static List<String> documentedKeys() {
    return PAGES.stream()
        .flatMap(page -> SPRING_KEY.matcher(read(page)).results())
        .map(MatchResult::group)
        .distinct()
        .toList();
  }

  /** Returns a page's text. */
  private static String read(Path page) {
    try {
      return Files.readString(page);
    } catch (IOException readFailure) {
      throw new UncheckedIOException(readFailure);
    }
  }

  @Nested
  @DisplayName("keys for every client")
  class EveryClientKeys {

    @Test
    @DisplayName("each is a property or group in Spring Boot's metadata, and is not deprecated")
    void listedAndNotDeprecated() throws IOException {
      var keys =
          documentedKeys().stream().filter(key -> !key.startsWith(SERVICE_CLIENT_PREFIX)).toList();
      var deprecatedByName =
          Collections.list(Thread.currentThread().getContextClassLoader().getResources(METADATA))
              .stream()
              .flatMap(metadataFile -> entriesOf(metadataFile).stream())
              .collect(
                  Collectors.toMap(
                      entry -> (String) entry.get("name"),
                      entry -> Boolean.TRUE.equals(entry.get("deprecated")),
                      Boolean::logicalOr));

      assertThat(keys).isNotEmpty();
      assertThat(deprecatedByName).containsKeys(keys.toArray(String[]::new));
      assertThat(keys.stream().filter(deprecatedByName::get)).as("deprecated keys").isEmpty();
    }

    /** Returns the properties and groups a metadata file lists. */
    private List<Map<String, Object>> entriesOf(URL metadataFile) {
      try (InputStream metadata = metadataFile.openStream()) {
        Map<String, List<Map<String, Object>>> listsByKind =
            JsonPath.read(new String(metadata.readAllBytes(), StandardCharsets.UTF_8), "$");
        return METADATA_LISTS.stream()
            .flatMap(kind -> listsByKind.getOrDefault(kind, List.of()).stream())
            .toList();
      } catch (IOException readFailure) {
        throw new UncheckedIOException(readFailure);
      }
    }
  }

  @Nested
  @DisplayName("keys of one named client")
  class NamedClientKeys {

    @Test
    @DisplayName("each binds to Spring Boot's settings of one client")
    void bindToClientSettings() {
      var clientPrefix = SERVICE_CLIENT_PREFIX + CLIENT_NAME;
      var keys = documentedKeys().stream().filter(key -> key.startsWith(clientPrefix)).toList();

      assertThat(keys).isNotEmpty();
      assertThat(keys)
          .allSatisfy(
              key -> assertThatNoException().as(key).isThrownBy(() -> bindOnly(clientPrefix, key)));
    }

    /** Binds one key to a client's settings, failing if Spring Boot does not know the key. */
    private void bindOnly(String clientPrefix, String key) {
      var source = new MapConfigurationPropertySource(Map.of(key, sampleValueFor(key)));
      new Binder(source)
          .bind(
              clientPrefix,
              Bindable.of(HttpClientProperties.class),
              new NoUnboundElementsBindHandler(BindHandler.DEFAULT));
    }

    /** Returns a value of the right kind for a key: a duration for a timeout, else any text. */
    private String sampleValueFor(String key) {
      return key.endsWith("-timeout") ? "1s" : "https://inventory.internal";
    }
  }
}
