package com.exepex.stratum.buildrules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Compares the Stratum entries of a BOM with the modules a reactor publishes, and returns each
 * difference as a sentence that names the module.
 *
 * <p>A published module is a {@code <module>} of the root {@code pom.xml} outside {@code platform/}
 * whose own {@code <properties>} do not set {@code skipPublishing} to {@code true}; its name is its
 * {@code <artifactId>}. A Stratum entry is a BOM dependency whose {@code <groupId>} is Stratum's.
 * The entries must be in strictly increasing {@link String#compareTo} order, so a name listed twice
 * is out of order too.
 *
 * @param root the directory of the reactor's root {@code pom.xml}
 * @param bom the BOM's {@code pom.xml}
 */
record BomCheck(Path root, Path bom) {

  /**
   * The repository's root, for the tests that read this repository's POMs: Surefire runs this
   * module's tests in its own directory, which is two levels below the root.
   */
  static final Path REPOSITORY_ROOT =
      Path.of(System.getProperty("basedir", System.getProperty("user.dir")))
          .resolve("../..")
          .normalize();

  /** The group of every Stratum module; the BOM's other entries are third-party libraries. */
  static final String STRATUM_GROUP = "io.github.exepex.stratum";

  /** The directory of the parent POMs, which are not in the BOM. */
  private static final String PLATFORM_DIRECTORY = "platform/";

  /**
   * Returns every difference between the BOM's Stratum entries and the modules the reactor
   * publishes.
   *
   * @return one sentence per difference, each naming its module: first the missing modules, then
   *     the entries that are not published modules, the repeated entries and the entries out of
   *     name order; empty when the BOM and the reactor match
   */
  List<String> problems() {
    List<String> published = publishedModules();
    List<String> entries = stratumEntries();
    Stream<String> missing =
        published.stream()
            .filter(module -> !entries.contains(module))
            .map(module -> "missing from the BOM: " + module);
    Stream<String> unknown =
        entries.stream()
            .filter(entry -> !published.contains(entry))
            .map(entry -> "in the BOM but not a published module: " + entry);
    Stream<String> repeated =
        IntStream.range(0, entries.size())
            .filter(index -> entries.subList(0, index).contains(entries.get(index)))
            .mapToObj(index -> "listed twice in the BOM: " + entries.get(index));
    Stream<String> unordered =
        IntStream.range(1, entries.size())
            .filter(index -> entries.get(index).compareTo(entries.get(index - 1)) <= 0)
            .mapToObj(
                index ->
                    "out of name order in the BOM: "
                        + entries.get(index)
                        + " after "
                        + entries.get(index - 1));
    return Stream.of(missing, unknown, repeated, unordered).flatMap(problem -> problem).toList();
  }

  /** Returns the artifactIds of the modules the reactor publishes, in the root POM's order. */
  private List<String> publishedModules() {
    return children(child(pom(root), "modules"), "module")
        .map(module -> module.getTextContent().trim())
        .filter(path -> !path.startsWith(PLATFORM_DIRECTORY))
        .map(path -> pom(root.resolve(path)))
        .filter(project -> !skipsPublishing(project))
        .map(this::artifactId)
        .toList();
  }

  /** Returns the artifactIds of the BOM's Stratum entries, in the order the BOM lists them. */
  private List<String> stratumEntries() {
    Element management = child(parse(bom), "dependencyManagement");
    return children(child(management, "dependencies"), "dependency")
        .filter(dependency -> STRATUM_GROUP.equals(text(child(dependency, "groupId"))))
        .map(this::artifactId)
        .toList();
  }

  /** Returns whether a module's own {@code <properties>} set {@code skipPublishing} to true. */
  private boolean skipsPublishing(Element project) {
    return children(project, "properties")
        .flatMap(properties -> children(properties, "skipPublishing"))
        .anyMatch(skip -> "true".equals(text(skip)));
  }

  /**
   * Returns the {@link BomCheck} of this repository's {@code stratum-bom}, which also reads any of
   * its POMs.
   *
   * @return the check
   */
  static BomCheck ofRepository() {
    return new BomCheck(REPOSITORY_ROOT, REPOSITORY_ROOT.resolve("platform/stratum-bom/pom.xml"));
  }

  /**
   * Returns the root element of the {@code pom.xml} in {@code directory}, parsed with DOCTYPE
   * declarations refused.
   *
   * @param directory the module's directory
   * @return the project element
   */
  Element pom(Path directory) {
    return parse(directory.resolve("pom.xml"));
  }

  /**
   * Returns the {@code <artifactId>} of a module's project element or of a dependency.
   *
   * @param projectOrDependency the element
   * @return its artifactId
   */
  String artifactId(Element projectOrDependency) {
    return text(child(projectOrDependency, "artifactId"));
  }

  /** Parses {@code pom} with DOCTYPE declarations refused, and returns its root element. */
  private Element parse(Path pom) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      try (var pomContent = Files.newInputStream(pom)) {
        return factory.newDocumentBuilder().parse(pomContent).getDocumentElement();
      }
    } catch (IOException | ParserConfigurationException | SAXException unreadablePom) {
      throw new IllegalStateException("Cannot read " + pom, unreadablePom);
    }
  }

  /**
   * Returns the first child element of {@code parent} named {@code name}.
   *
   * @param parent the element
   * @param name the child's name
   * @return the child
   */
  Element child(Element parent, String name) {
    return children(parent, name)
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("No <" + name + "> in <" + parent.getTagName() + ">"));
  }

  /**
   * Returns the child elements of {@code parent} named {@code name}, in document order.
   *
   * @param parent the element
   * @param name the children's name
   * @return the children
   */
  Stream<Element> children(Element parent, String name) {
    return Stream.iterate(parent.getFirstChild(), Objects::nonNull, Node::getNextSibling)
        .filter(Element.class::isInstance)
        .map(Element.class::cast)
        .filter(element -> name.equals(element.getTagName()));
  }

  /**
   * Returns the trimmed text of {@code element}.
   *
   * @param element the element
   * @return its text
   */
  String text(Element element) {
    return element.getTextContent().trim();
  }
}
