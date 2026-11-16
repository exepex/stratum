package com.exepex.stratum.buildrules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 */
final class BomCheck {

  /** The group of every Stratum module; the BOM's other entries are third-party libraries. */
  static final String STRATUM_GROUP = "io.github.exepex.stratum";

  /** The directory of the parent POMs, which are not in the BOM. */
  private static final String PLATFORM_DIRECTORY = "platform/";

  private BomCheck() {}

  /**
   * Returns every difference between the Stratum entries of {@code bom} and the modules that the
   * reactor at {@code root} publishes, or an empty list when they match.
   */
  static List<String> problems(Path root, Path bom) {
    List<String> published = publishedModules(root);
    List<String> entries = stratumEntries(bom);
    List<String> problems = new ArrayList<>();
    for (String module : published) {
      if (!entries.contains(module)) {
        problems.add("missing from the BOM: " + module);
      }
    }
    Set<String> seen = new HashSet<>();
    String previous = null;
    for (String entry : entries) {
      if (!published.contains(entry)) {
        problems.add("in the BOM but not a published module: " + entry);
      }
      if (!seen.add(entry)) {
        problems.add("listed twice in the BOM: " + entry);
      }
      if (previous != null && entry.compareTo(previous) <= 0) {
        problems.add("out of name order in the BOM: " + entry + " after " + previous);
      }
      previous = entry;
    }
    return problems;
  }

  /** Returns the artifactIds of the modules the reactor at {@code root} publishes. */
  private static List<String> publishedModules(Path root) {
    List<String> published = new ArrayList<>();
    Element modules = child(parse(root.resolve("pom.xml")), "modules");
    for (Element module : children(modules, "module")) {
      String path = module.getTextContent().trim();
      if (path.startsWith(PLATFORM_DIRECTORY)) {
        continue;
      }
      Element project = parse(root.resolve(path).resolve("pom.xml"));
      Element properties = child(project, "properties");
      Element skip = properties == null ? null : child(properties, "skipPublishing");
      if (skip == null || !"true".equals(skip.getTextContent().trim())) {
        published.add(child(project, "artifactId").getTextContent().trim());
      }
    }
    return published;
  }

  /** Returns the artifactIds of the Stratum entries of {@code bom}, in the order it lists them. */
  private static List<String> stratumEntries(Path bom) {
    Element management = child(parse(bom), "dependencyManagement");
    List<String> entries = new ArrayList<>();
    for (Element dependency : children(child(management, "dependencies"), "dependency")) {
      if (STRATUM_GROUP.equals(child(dependency, "groupId").getTextContent().trim())) {
        entries.add(child(dependency, "artifactId").getTextContent().trim());
      }
    }
    return entries;
  }

  /** Parses {@code pom} with DOCTYPE declarations refused, and returns its root element. */
  private static Element parse(Path pom) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      try (var in = Files.newInputStream(pom)) {
        return factory.newDocumentBuilder().parse(in).getDocumentElement();
      }
    } catch (IOException | ParserConfigurationException | SAXException e) {
      throw new IllegalStateException("Cannot read " + pom, e);
    }
  }

  /** Returns the first child element of {@code parent} named {@code name}, or null. */
  private static Element child(Element parent, String name) {
    List<Element> found = children(parent, name);
    return found.isEmpty() ? null : found.get(0);
  }

  /** Returns the child elements of {@code parent} named {@code name}, in document order. */
  private static List<Element> children(Element parent, String name) {
    List<Element> found = new ArrayList<>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element && name.equals(element.getTagName())) {
        found.add(element);
      }
    }
    return found;
  }
}
