package com.exepex.stratum.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

@DisplayName("The resilience starter keeps its web, actuator and Micrometer dependencies optional")
class ResilienceOptionalDependenciesTest {

  /** This module's POM, relative to its directory, where Surefire runs the tests. */
  private static final Path POM = Path.of("pom.xml");

  /**
   * The dependencies a service must not get from this starter: Spring MVC with Tomcat, which only a
   * Spring MVC service has; actuator and its health settings; and Micrometer.
   */
  private static final List<String> OPTIONAL_DEPENDENCIES =
      List.of(
          "spring-boot-starter-webmvc",
          "spring-boot-actuator",
          "spring-boot-health",
          "micrometer-core");

  @ParameterizedTest
  @FieldSource("OPTIONAL_DEPENDENCIES")
  @DisplayName("each is declared optional, so a service that adds the starter does not get it")
  void declaredOptional(String artifactId) throws Exception {
    assertThat(optionalOf(artifactId))
        .as("%s must stay <optional>true</optional> in %s", artifactId, POM)
        .isEqualTo("true");
  }

  /** Returns the text of the dependency's {@code <optional>} element, or empty when it has none. */
  private String optionalOf(String artifactId)
      throws IOException, ParserConfigurationException, SAXException {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    try (var pom = Files.newInputStream(POM)) {
      var dependencies = factory.newDocumentBuilder().parse(pom).getElementsByTagName("dependency");
      return IntStream.range(0, dependencies.getLength())
          .mapToObj(index -> (Element) dependencies.item(index))
          .filter(dependency -> artifactId.equals(textOf(dependency, "artifactId")))
          .map(dependency -> textOf(dependency, "optional"))
          .findFirst()
          .orElseThrow(() -> new AssertionError(artifactId + " is not a dependency of " + POM));
    }
  }

  /** Returns the trimmed text of the element's first child with the name, or empty. */
  private String textOf(Element parent, String name) {
    var children = parent.getElementsByTagName(name);
    return children.getLength() == 0
        ? ""
        : Objects.requireNonNull(children.item(0).getTextContent()).trim();
  }
}
