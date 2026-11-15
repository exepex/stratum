package com.exepex.stratum.datajpa.routing;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.data.UseDataSource;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Maps each JPA repository to its pool: the pool its {@code @UseDataSource} names, else the pool of
 * the most specific mapped package that holds it, else none. It checks every mapped package and
 * every annotation, and fails startup on the first that breaks a rule.
 */
@StratumInternal
public final class RepositoryMapping {

  /** The character between a package's segments. */
  private static final char DOT = '.';

  /** Each mapped package and its pool. */
  private final Map<String, String> poolByPackage;

  /** Each pool's packages, as set; a pool that maps none is left out. */
  private final Map<String, List<String>> packagesByPool;

  /** The named pools of the application. */
  private final Set<String> pools;

  /**
   * Checks the mapped packages and keeps them.
   *
   * @param packages each pool's {@code packages}, as set
   * @param pools the named pools of the application
   * @throws StratumException {@code SX-DATA-1007} if a pool that maps packages does not exist, an
   *     entry is no package name, or a package is mapped to two pools
   */
  public RepositoryMapping(Map<String, List<String>> packages, Set<String> pools) {
    this.pools = Set.copyOf(pools);
    var byPackage = new HashMap<String, String>();
    packages.forEach((pool, names) -> addPool(byPackage, pool, names));
    poolByPackage = Map.copyOf(byPackage);
    packagesByPool =
        packages.entrySet().stream()
            .filter(entry -> !entry.getValue().isEmpty())
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
  }

  /**
   * Maps each repository to its pool.
   *
   * @param repositories the JPA repository interfaces of the application
   * @return the mapping, which is empty when no package is mapped and no repository is annotated
   * @throws StratumException {@code SX-DATA-1007} if an annotation names an unknown pool, or a
   *     mapped package holds no JPA repository
   */
  public RepositoryPools map(Collection<Class<?>> repositories) {
    Map<Class<?>, String> mapped =
        repositories.stream()
            .flatMap(repository -> poolEntry(repository).stream())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    var annotated =
        repositories.stream()
            .filter(repository -> repository.isAnnotationPresent(UseDataSource.class))
            .collect(
                Collectors.groupingBy(
                    mapped::get,
                    TreeMap::new,
                    Collectors.mapping(Class::getName, Collectors.toList())));
    var result =
        RepositoryPools.builder()
            .pools(mapped)
            .packages(packagesByPool)
            .annotated(annotated)
            .build();
    if (!result.isEmpty()) {
      checkUsed(repositories);
    }
    return result;
  }

  /** Returns a repository with its pool, or nothing if it has none. */
  private Optional<Map.Entry<Class<?>, String>> poolEntry(Class<?> repository) {
    return Optional.ofNullable(poolOf(repository)).map(pool -> Map.entry(repository, pool));
  }

  /** Returns a repository's pool: its annotation's, else its package's, else none. */
  private @Nullable String poolOf(Class<?> repository) {
    var annotation = repository.getAnnotation(UseDataSource.class);
    return annotation != null
        ? checkedPool(annotation.value(), repository)
        : poolOfPackage(repository.getPackageName());
  }

  /** Checks one pool's packages and adds each to the map of packages to pools. */
  private void addPool(Map<String, String> byPackage, String pool, List<String> packages) {
    if (!packages.isEmpty() && !pools.contains(pool)) {
      throw invalid(
          "%s.%s.%s maps packages to a pool that does not exist; named pools need HikariCP"
              .formatted(DataConstants.SOURCES, pool, DataConstants.PACKAGES_KEY));
    }
    IntStream.range(0, packages.size())
        .forEach(index -> addPackage(byPackage, pool, packages.get(index), index));
  }

  /** Checks one entry of a pool's packages and adds it to the map of packages to pools. */
  private void addPackage(Map<String, String> byPackage, String pool, String name, int index) {
    if (!isPackageName(name)) {
      throw invalid(
          "%s.%s.%s[%d] is not a Java package name"
              .formatted(DataConstants.SOURCES, pool, DataConstants.PACKAGES_KEY, index));
    }
    var other = byPackage.putIfAbsent(name, pool);
    if (other != null) {
      throw invalid("the package %s is mapped to the pools %s and %s".formatted(name, other, pool));
    }
  }

  /** Returns the pool an annotation names, if it is main or a named pool. */
  private String checkedPool(String pool, Class<?> repository) {
    if (!DataConstants.MAIN_POOL.equals(pool) && !pools.contains(pool)) {
      throw invalid(
          "@UseDataSource on %s names the pool %s, which does not exist"
              .formatted(repository.getName(), pool));
    }
    return pool;
  }

  /**
   * Returns the pool of the most specific mapped package that holds a package: the package itself,
   * else its parent, and so on, so that {@code com.acme} holds {@code com.acme.x}, never {@code
   * com.acmex}.
   */
  private @Nullable String poolOfPackage(String packageName) {
    return withParents(packageName)
        .map(poolByPackage::get)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  /** Returns a package and each of its parents, the package first. */
  private Stream<String> withParents(String packageName) {
    return Stream.iterate(packageName, Objects::nonNull, this::parentOf);
  }

  /** Returns a package's parent, or none for a top-level package. */
  private @Nullable String parentOf(String packageName) {
    var dot = packageName.lastIndexOf(DOT);
    return dot < 0 ? null : packageName.substring(0, dot);
  }

  /** Fails when a mapped package holds no JPA repository, which is most likely a typo. */
  private void checkUsed(Collection<Class<?>> repositories) {
    var holding =
        repositories.stream()
            .map(Class::getPackageName)
            .flatMap(this::withParents)
            .collect(Collectors.toUnmodifiableSet());
    var unused =
        packagesByPool.entrySet().stream()
            .flatMap(
                entry ->
                    entry.getValue().stream()
                        .filter(name -> !holding.contains(name))
                        .map(name -> unusedPackage(name, entry.getKey())))
            .findFirst();
    if (unused.isPresent()) {
      throw unused.get();
    }
  }

  /** Returns the startup failure of a mapped package that holds no JPA repository. */
  private StratumException unusedPackage(String name, String pool) {
    return invalid("the package %s of pool %s holds no JPA repository".formatted(name, pool));
  }

  /**
   * Returns whether a value is a package name: segments of Java identifier characters, joined by
   * single dots, with no wildcard. It reads each character once, in a loop rather than a stream,
   * since each character's rule depends on the one before it (ST-ENG-39).
   */
  private boolean isPackageName(@Nullable String name) {
    if (name == null || name.isEmpty()) {
      return false;
    }
    var segmentStart = true;
    for (var index = 0; index < name.length(); index++) {
      var character = name.charAt(index);
      if (character == DOT) {
        if (segmentStart) {
          return false;
        }
        segmentStart = true;
      } else if (isIdentifierCharacter(character, segmentStart)) {
        segmentStart = false;
      } else {
        return false;
      }
    }
    return !segmentStart;
  }

  /** Returns whether a character may stand at its place in a Java identifier. */
  private boolean isIdentifierCharacter(char character, boolean first) {
    return first
        ? Character.isJavaIdentifierStart(character)
        : Character.isJavaIdentifierPart(character);
  }

  /** Returns the startup failure of a mapping that breaks a rule. */
  private StratumException invalid(String message) {
    return new StratumException(DataErrorCode.ROUTING_INVALID, message);
  }
}
