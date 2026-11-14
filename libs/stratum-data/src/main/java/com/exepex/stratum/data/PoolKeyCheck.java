package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.util.PropertyElf;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName.Form;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.core.env.Environment;

/**
 * Stops startup when a named pool's name or key breaks its rules, so that a misspelt key never
 * leaves a pool silently without it. Every key under {@code stratum.data.sources} is compared in
 * Spring Boot's uniform form, without dots, dashes or case, so each spelling Boot binds, such as
 * {@code STRATUM_DATA_SOURCES_BATCH_QUERY_TIMEOUT}, passes, and one it would drop fails.
 */
@StratumInternal
public class PoolKeyCheck {

  /** A pool's name: lower-case letters and digits, starting with a letter, at most 31. */
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]{0,30}");

  /** The keys of a pool other than HikariCP's, in uniform form. */
  private static final Set<String> KEYS =
      Set.of("url", "username", "password", "querytimeout", "transactiontimeout", "fetchsize");

  /** The key under which HikariCP's own settings follow. */
  private static final String HIKARI = "hikari";

  /** HikariCP's settings that hold a map, whose own keys are the driver's or the check's. */
  private static final List<String> HIKARI_MAPS =
      List.of("datasourceproperties", "healthcheckproperties");

  /** Where the pools' keys start: {@code stratum.data.sources}. */
  private static final ConfigurationPropertyName SOURCES =
      ConfigurationPropertyName.of(DataConstants.SOURCES);

  /** Where a pool's name is among a key's elements. */
  private static final int NAME_ELEMENT = 3;

  /** The line breaks a key in a message must not carry. */
  private static final String LINE_BREAKS = "[\r\n]";

  /** HikariCP's setting names, in uniform form. */
  private final Set<String> hikariKeys =
      PropertyElf.getPropertyNames(HikariConfig.class).stream()
          .map(key -> key.toLowerCase(Locale.ROOT))
          .collect(Collectors.toUnmodifiableSet());

  /**
   * Checks every key under {@code stratum.data.sources} in the environment, before the keys are
   * bound, so that a bad key is named rather than reported as a binding failure.
   *
   * @param environment the application's environment
   * @throws StratumException {@code SX-DATA-1004} naming the first key that breaks a rule
   */
  void checkKeys(Environment environment) {
    for (var source : ConfigurationPropertySources.get(environment)) {
      if (source instanceof IterableConfigurationPropertySource keys) {
        keys.stream().filter(SOURCES::isAncestorOf).forEach(this::checkKey);
      }
    }
  }

  /**
   * Checks the name and HikariCP keys of every pool in the settings, which includes those a Java
   * customizer adds.
   *
   * @param settings the module's settings
   * @throws StratumException {@code SX-DATA-1004} naming the first pool or key that breaks a rule
   */
  void checkSettings(DataProperties settings) {
    settings.sources().forEach(this::checkPool);
  }

  /**
   * Checks a pool's name.
   *
   * @param name the pool's name
   * @throws StratumException {@code SX-DATA-1004} if the name breaks its rule or is {@code main}
   */
  void checkName(String name) {
    if (!isName(name)) {
      throw invalid(
          ("the pool name in %s.%s is not lower-case letters and digits starting with a letter, at"
                  + " most 31 characters, or it is main")
              .formatted(DataConstants.SOURCES, clean(name)));
    }
  }

  /**
   * Returns whether a text follows the rule of a pool's name and is not {@code main}.
   *
   * @param name the text
   * @return whether it can name a pool
   */
  boolean isName(String name) {
    return NAME.matcher(name).matches() && !DataConstants.MAIN_POOL.equals(name);
  }

  /** Checks the name and the HikariCP keys of a pool in the settings. */
  private void checkPool(String name, PoolSettings pool) {
    checkName(name);
    pool.hikari().keySet().stream()
        .filter(key -> !isHikariKey(uniform(key)))
        .findFirst()
        .ifPresent(key -> unknown(name, HIKARI + "." + key));
  }

  /**
   * Checks one key under {@code stratum.data.sources}: its pool's name and its own name. A HikariCP
   * key is bound into the pool's {@code hikari} map in every spelling, so {@link #checkSettings}
   * checks it there.
   */
  private void checkKey(ConfigurationPropertyName key) {
    if (key.getNumberOfElements() <= NAME_ELEMENT + 1) {
      throw invalid("%s is not a key of a named pool".formatted(clean(key.toString())));
    }
    checkName(key.getElement(NAME_ELEMENT, Form.ORIGINAL));
    boolean hikari = HIKARI.equals(key.getElement(NAME_ELEMENT + 1, Form.UNIFORM));
    if (!hikari && !KEYS.contains(uniformFrom(key, NAME_ELEMENT + 1))) {
      throw invalid("%s is not a key of a named pool".formatted(clean(key.toString())));
    }
  }

  /** Returns whether a key after {@code hikari.}, in uniform form, is one of HikariCP's. */
  private boolean isHikariKey(String rest) {
    return hikariKeys.contains(rest)
        || HIKARI_MAPS.stream()
            .anyMatch(map -> rest.startsWith(map) && rest.length() > map.length());
  }

  /** Returns a key's elements from one on, in uniform form, joined with nothing between. */
  private String uniformFrom(ConfigurationPropertyName key, int first) {
    return IntStream.range(first, key.getNumberOfElements())
        .mapToObj(index -> key.getElement(index, Form.UNIFORM))
        .collect(Collectors.joining());
  }

  /** Returns a key a Java customizer wrote, in uniform form. */
  private String uniform(String key) {
    return key.toLowerCase(Locale.ROOT).replaceAll("[-._\\[\\]]", "");
  }

  /** Fails for a HikariCP key of a pool in the settings that HikariCP does not have. */
  private void unknown(String name, String key) {
    throw invalid(
        "%s.%s.%s is not a key of a named pool".formatted(DataConstants.SOURCES, name, clean(key)));
  }

  /** Returns a key without line breaks, for a message. */
  private String clean(String key) {
    return key.replaceAll(LINE_BREAKS, " ");
  }

  /** Returns the startup failure with its detail. */
  private StratumException invalid(String detail) {
    return new StratumException(DataErrorCode.POOL_SETTING_INVALID, detail);
  }
}
