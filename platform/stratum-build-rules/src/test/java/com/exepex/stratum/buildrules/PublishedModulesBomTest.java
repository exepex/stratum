package com.exepex.stratum.buildrules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * Checks that {@code stratum-bom} lists exactly the modules this repository publishes, in name
 * order, and that {@link BomCheck} reports each kind of difference in a fixture reactor.
 */
@DisplayName("stratum-bom lists exactly the published modules, in name order")
class PublishedModulesBomTest {

  @Nested
  @DisplayName("in this repository")
  class InThisRepository {

    /**
     * The repository's root: Surefire runs this module's tests in its own directory, which is two
     * levels below the root.
     */
    private static final Path ROOT =
        Path.of(System.getProperty("basedir", System.getProperty("user.dir")))
            .resolve("../..")
            .normalize();

    @Test
    @DisplayName("the BOM and the published modules match")
    void match() {
      assertThat(ROOT.resolve("platform/stratum-build-rules")).isDirectory();
      assertThat(new BomCheck(ROOT, ROOT.resolve("platform/stratum-bom/pom.xml")).problems())
          .isEmpty();
    }
  }

  @Nested
  @DisplayName("in a fixture reactor")
  class InAFixtureReactor {

    /** Each BOM in {@code bom/boms} that differs from the fixture reactor, and what is reported. */
    private static final List<Arguments> BOMS_AND_PROBLEMS =
        List.of(
            arguments("missing.xml", List.of("missing from the BOM: stratum-beta")),
            arguments("extra.xml", List.of("in the BOM but not a published module: stratum-delta")),
            arguments(
                "unpublished.xml",
                List.of("in the BOM but not a published module: stratum-starter-gamma")),
            arguments(
                "unordered.xml",
                List.of("out of name order in the BOM: stratum-alpha after stratum-beta")),
            arguments(
                "duplicate.xml",
                List.of(
                    "listed twice in the BOM: stratum-beta",
                    "out of name order in the BOM: stratum-beta after stratum-beta")));

    @Test
    @DisplayName(
        "a BOM that lists the published modules in order, and third-party entries in any order,"
            + " passes")
    void passes() {
      assertThat(new BomCheck(reactor(), fixture("boms/valid.xml")).problems()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("BOMS_AND_PROBLEMS")
    @DisplayName("a BOM that differs is reported by module name")
    void reports(String bom, List<String> problems) {
      assertThat(new BomCheck(reactor(), fixture("boms/" + bom)).problems())
          .containsExactlyElementsOf(problems);
    }

    @Test
    @DisplayName("a BOM that declares a DOCTYPE is refused, and the error names its file")
    void refusesDoctype() {
      Path bom = fixture("boms/doctype.xml");
      assertThatThrownBy(() -> new BomCheck(reactor(), bom).problems())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(bom.toString())
          .rootCause()
          .hasMessageContaining("DOCTYPE");
    }

    @Test
    @DisplayName("a BOM without <dependencyManagement> is refused, and the error names the element")
    void refusesIncompleteBom() {
      assertThatThrownBy(() -> new BomCheck(reactor(), reactor().resolve("pom.xml")).problems())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("No <dependencyManagement> in <project>");
    }

    /** Returns the fixture reactor's root directory. */
    private Path reactor() {
      return fixture("reactor");
    }

    /** Returns the path of a file or directory under this package's {@code bom} test resources. */
    private Path fixture(String name) {
      try {
        return Path.of(PublishedModulesBomTest.class.getResource("bom/" + name).toURI());
      } catch (URISyntaxException invalidResourceUri) {
        throw new IllegalStateException(invalidResourceUri);
      }
    }
  }
}
