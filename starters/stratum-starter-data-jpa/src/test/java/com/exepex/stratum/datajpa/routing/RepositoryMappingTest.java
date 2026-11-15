package com.exepex.stratum.datajpa.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.data.UseDataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("RepositoryMapping maps each repository to its pool and fails on a broken mapping")
class RepositoryMappingTest {

  private static final String BATCH = "batch";

  private static final String REPORTING = "reporting";

  /** A package that holds interfaces the tests use as repositories. */
  private static final String JAVA_UTIL = "java.util";

  /** The named pools of the application. */
  private static final Set<String> POOLS = Set.of(BATCH, REPORTING);

  /** Returns the mapping of the given packages, as one pool's. */
  private RepositoryMapping mappingOf(String pool, String... packages) {
    return new RepositoryMapping(Map.of(pool, List.of(packages)), POOLS);
  }

  /** Returns the failure of a mapping step that must fail with {@code SX-DATA-1007}. */
  private StratumException failureOf(Runnable step) {
    var failure = catchThrowableOfType(StratumException.class, step::run);
    assertThat(failure.getErrorCode()).isEqualTo(DataErrorCode.ROUTING_INVALID);
    return failure;
  }

  @Nested
  @DisplayName("package names")
  class PackageNames {

    /** Entries that are no package name. */
    private static final List<String> NOT_PACKAGES =
        List.of(
            "",
            " ",
            "*",
            "com.*",
            ".com",
            "com.",
            "com..acme",
            "com.1acme",
            "com.ac-me",
            "com acme");

    /** Entries that are package names, Java's rare characters included. */
    private static final List<String> PACKAGES =
        List.of("java", JAVA_UTIL, "java.util_x", "java.$util", "java.café");

    @ParameterizedTest(name = "\"{0}\"")
    @FieldSource("NOT_PACKAGES")
    @DisplayName("an entry that is no package name fails, named by its key and index only")
    void notPackageFails(String entry) {
      var failure = failureOf(() -> mappingOf(BATCH, JAVA_UTIL, entry));

      assertThat(failure)
          .hasMessageContaining(
              "stratum.data.sources.batch.packages[1] is not a Java package name");
      assertThat(failure.getMessage()).doesNotContain("'" + entry + "'");
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("PACKAGES")
    @DisplayName("a package name passes")
    void packagePasses(String entry) {
      assertThatCode(() -> mappingOf(BATCH, entry)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a package mapped to two pools fails")
    void twoPoolsFail() {
      var packages = Map.of(BATCH, List.of(JAVA_UTIL), REPORTING, List.of(JAVA_UTIL));

      assertThat(failureOf(() -> new RepositoryMapping(packages, POOLS)))
          .hasMessageContaining("the package java.util is mapped to the pools");
    }
  }

  @Nested
  @DisplayName("which pool a repository gets")
  class Precedence {

    @Test
    @DisplayName("the most specific package wins")
    void mostSpecificPackage() {
      var packages = Map.of(BATCH, List.of(JAVA_UTIL), REPORTING, List.of("java.util.concurrent"));

      var pools = new RepositoryMapping(packages, POOLS).map(List.of(List.class, Callable.class));

      assertThat(pools.poolOf(List.class)).isEqualTo(BATCH);
      assertThat(pools.poolOf(Callable.class)).isEqualTo(REPORTING);
    }

    @Test
    @DisplayName("a package holds its subpackages only, never a package that merely starts alike")
    void segmentBoundary() {
      var pools = mappingOf(BATCH, "java").map(List.of(List.class, DataSource.class));

      assertThat(pools.poolOf(List.class)).isEqualTo(BATCH);
      assertThat(pools.poolOf(DataSource.class)).isNull();
    }

    @Test
    @DisplayName("@UseDataSource wins over a mapped package")
    void annotationWins() {
      var pools =
          mappingOf(BATCH, RepositoryMappingTest.class.getPackageName())
              .map(List.of(OnReporting.class, Unannotated.class));

      assertThat(pools.poolOf(OnReporting.class)).isEqualTo(REPORTING);
      assertThat(pools.poolOf(Unannotated.class)).isEqualTo(BATCH);
      assertThat(pools.annotated()).containsOnlyKeys(REPORTING);
    }

    @Test
    @DisplayName("@UseDataSource(\"main\") is allowed, wins, and adds no routing target")
    void annotationMayNameMain() {
      var pools =
          mappingOf(BATCH, RepositoryMappingTest.class.getPackageName())
              .map(List.of(OnMain.class, Unannotated.class));

      assertThat(pools.poolOf(OnMain.class)).isEqualTo("main");
      assertThat(pools.targets()).containsExactly(BATCH);
    }
  }

  @Nested
  @DisplayName("the checks of the whole mapping")
  class WholeMapping {

    @Test
    @DisplayName("@UseDataSource naming a pool that does not exist fails")
    void unknownPoolFails() {
      var mapping = new RepositoryMapping(Map.of(), POOLS);

      assertThat(failureOf(() -> mapping.map(List.of(OnUnknown.class))))
          .hasMessageContaining("names the pool exports, which does not exist");
    }

    @Test
    @DisplayName("a mapped package that holds no repository fails")
    void unusedPackageFails() {
      var mapping = mappingOf(BATCH, JAVA_UTIL, "java.time");

      assertThat(failureOf(() -> mapping.map(List.of(List.class))))
          .hasMessageContaining("the package java.time of pool batch holds no JPA repository");
    }

    @Test
    @DisplayName("with no package and no annotation, the mapping is empty, and nothing is checked")
    void emptyMapping() {
      var pools = new RepositoryMapping(Map.of(BATCH, List.of()), POOLS).map(List.of(List.class));

      assertThat(pools.isEmpty()).isTrue();
      assertThat(pools.targets()).isEmpty();
    }
  }

  /** A repository in this test's package with no annotation. */
  interface Unannotated {}

  /** A repository that names the pool reporting. */
  @UseDataSource(REPORTING)
  interface OnReporting {}

  /** A repository that names main. */
  @UseDataSource("main")
  interface OnMain {}

  /** A repository that names a pool the application does not have. */
  @UseDataSource("exports")
  interface OnUnknown {}
}
