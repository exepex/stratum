package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.api.StratumInternal;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/**
 * Checks the defaults a module's generated configuration metadata lists, which IDEs and the
 * configuration reference show, against the values the module uses.
 */
@StratumInternal
@UtilityClass
public class MetadataDefaultChecks {

  /** The metadata in a module's jar, relative to the module's directory. */
  static final Path METADATA =
      Path.of("target/classes/META-INF/spring-configuration-metadata.json");

  /**
   * Checks that each default the module's metadata lists is the value the module uses when the
   * property is unset: setting the property to its listed default changes nothing the module uses.
   *
   * @param inEffect what the module uses, such as its resolved settings, from the given properties
   * @throws IOException if the metadata cannot be read
   */
  public static void checkMetadataDefaults(Function<Map<String, String>, ?> inEffect)
      throws IOException {
    checkMetadataDefaults(METADATA, inEffect);
  }

  /**
   * Checks the defaults that one metadata file lists, as {@link #checkMetadataDefaults(Function)}
   * does.
   *
   * @param metadata the metadata file
   * @param inEffect what the module uses, from the given properties
   * @throws IOException if the metadata cannot be read
   */
  static void checkMetadataDefaults(Path metadata, Function<Map<String, String>, ?> inEffect)
      throws IOException {
    var unset = inEffect.apply(Map.of());
    List<Map<String, Object>> properties =
        JsonPath.read(Files.readString(metadata), "$.properties[*]");

    assertThat(properties)
        .filteredOn(property -> property.containsKey("defaultValue"))
        .isNotEmpty()
        .allSatisfy(property -> assertListedDefaultInEffect(property, inEffect, unset));
  }

  /** Checks that one property set to its listed default leaves what the module uses unchanged. */
  private static void assertListedDefaultInEffect(
      Map<String, Object> property, Function<Map<String, String>, ?> inEffect, Object unset) {
    var name = String.valueOf(property.get("name"));
    var listed = propertyText(property.get("defaultValue"));

    assertThat(inEffect.apply(Map.of(name, listed)))
        .as("what the module uses with %s=%s, its listed default", name, listed)
        .isEqualTo(unset);
  }

  /** Returns a default as a property file writes it: a list as its items joined by commas. */
  private static String propertyText(Object defaultValue) {
    return defaultValue instanceof List<?> items
        ? items.stream().map(String::valueOf).collect(Collectors.joining(","))
        : String.valueOf(defaultValue);
  }
}
