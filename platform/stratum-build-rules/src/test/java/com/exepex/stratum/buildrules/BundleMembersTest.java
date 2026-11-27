package com.exepex.stratum.buildrules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * Checks that the Spring MVC bundles list exactly the single starters the bundle table of {@code
 * docs/modules/bundles.md} names, so removing a starter from a bundle, or adding one, fails the
 * build until the page says the same.
 */
@DisplayName("The Spring MVC bundles list exactly the starters the bundles page names")
class BundleMembersTest {

  /**
   * The repository's root: Surefire runs this module's tests in its own directory, which is two
   * levels below the root.
   */
  private static final Path ROOT =
      Path.of(System.getProperty("basedir", System.getProperty("user.dir")))
          .resolve("../..")
          .normalize();

  /** The prefix of every single starter's artifactId, which the bundles page leaves out. */
  private static final String STARTER_PREFIX = "stratum-starter-";

  /** Each Spring MVC bundle and the single starters it must list. */
  private static final List<Arguments> BUNDLES =
      List.of(
          arguments(
              "stratum-starter-microservice",
              Set.of("webmvc", "logging", "observability", "http-client", "cache", "resilience")),
          arguments(
              "stratum-starter-web-ui", Set.of("webmvc", "logging", "observability", "resilience")));

  @ParameterizedTest(name = "{0}")
  @FieldSource("BUNDLES")
  @DisplayName("the bundle's POM and the bundles page list the same single starters")
  void bundleMatchesPage(String bundle, Set<String> starters) throws Exception {
    assertThat(pomStarters(bundle)).as("starters in %s's POM", bundle).isEqualTo(starters);
    assertThat(pageStarters(bundle)).as("starters of %s on bundles.md", bundle).isEqualTo(starters);
  }

  /** Returns the single starters a bundle's POM depends on, without their prefix. */
  private Set<String> pomStarters(String bundle)
      throws IOException, ParserConfigurationException, SAXException {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    try (var pom = Files.newInputStream(ROOT.resolve("starters/" + bundle + "/pom.xml"))) {
      var artifacts = factory.newDocumentBuilder().parse(pom).getElementsByTagName("artifactId");
      return IntStream.range(0, artifacts.getLength())
          .mapToObj(index -> ((Element) artifacts.item(index)).getTextContent().trim())
          .filter(artifactId -> artifactId.startsWith(STARTER_PREFIX) && !artifactId.equals(bundle))
          .map(artifactId -> artifactId.substring(STARTER_PREFIX.length()))
          .collect(Collectors.toSet());
    }
  }

  /** Returns the single starters the bundles page's table names for a bundle. */
  private Set<String> pageStarters(String bundle) throws IOException {
    var row =
        Files.readAllLines(ROOT.resolve("docs/modules/bundles.md")).stream()
            .filter(line -> line.startsWith("| `" + bundle + "` |"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(bundle + " has no row on bundles.md"));
    var cells = row.split("\\|");
    return Arrays.stream(cells[cells.length - 1].split(","))
        .map(String::trim)
        .collect(Collectors.toSet());
  }
}
