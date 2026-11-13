import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Checks that the documentation index links every page under {@code docs/} in a table row, so no
 * page is left out of the wiki. The agent rules in {@code docs/agents/} are left out: AGENTS.md
 * indexes them, and they stay in the repository.
 */
final class IndexCoverage {

  /** The folder of the documentation pages. */
  private static final Path DOCS = Path.of("docs");

  /** The folder of the agent rules, which the index does not link. */
  private static final Path AGENT_RULES = Path.of("docs", "agents");

  /** The repository root that holds docs/. */
  private final Path repository;

  /**
   * Creates the check.
   *
   * @param repository the repository root that holds docs/
   */
  IndexCoverage(Path repository) {
    this.repository = repository;
  }

  /**
   * Fails, naming every missing page, when a Markdown page under docs/ is not among the copied pages.
   *
   * @param copied the repository paths of the pages the index links in its table rows
   */
  void check(Set<String> copied) {
    List<String> missing;
    try (Stream<Path> files = Files.walk(repository.resolve(DOCS))) {
      missing =
          files
              .map(repository::relativize)
              .filter(page -> page.toString().endsWith(".md") && !page.startsWith(AGENT_RULES))
              .map(page -> page.toString().replace('\\', '/'))
              .filter(page -> !copied.contains(page))
              .sorted()
              .toList();
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot list " + DOCS, failure);
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          WikiSync.INDEX + " links no table row to " + String.join(", ", missing) + "; add each to the index");
    }
  }
}
