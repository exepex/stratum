import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Copies the documentation pages and a generated sidebar from the repository into a clone of the
 * GitHub wiki. Usage: {@code java WikiSync.java <repository> <wiki> [--commit]}. Every check runs
 * before the first write; a failed check exits with 1 and leaves the wiki unchanged. Pages written
 * here carry a marker; a page without it was written in the wiki and is never changed or deleted,
 * except the hand-made Home, which the index replaces.
 */
final class WikiSync {

  /** The documentation index: the source of the page list, the sidebar and the wiki's Home. */
  private static final String INDEX = "docs/README.md";

  /** The project README: copied as its own page, because Home is the documentation index. */
  private static final String ROOT_README = "README.md";

  /** The wiki page of the project README. */
  private static final String ROOT_README_PAGE = "Project-README";

  /** The first line of every generated page, followed by its source path. */
  private static final String MARKER = "<!-- generated from ";

  /** Where links to repository files that are not copied lead. */
  private static final String BLOB = "https://github.com/exepex/stratum/blob/main/";

  /** The wiki's start page, the only unmarked page the sync may replace. */
  private static final String HOME = "Home";

  /** The wiki's sidebar file. */
  private static final String SIDEBAR = "_Sidebar.md";

  /** Index sections that become sidebar groups, mapped to their group, in sidebar order. */
  private static final Map<String, String> GROUPS =
      Map.of(
          "Getting started", "Getting started",
          "Configuration", "Configuration",
          "Guides", "Guides",
          "Contributing", "Guides",
          "Modules", "Modules",
          "Architecture decisions", "Decisions (ADRs)");

  /** The sidebar groups in the owner's order; audit reports follow them. */
  private static final List<String> GROUP_ORDER =
      List.of("Getting started", "Configuration", "Guides", "Modules", "Decisions (ADRs)");

  /** Index sections that list pages already in other sections, or none. */
  private static final Set<String> SKIPPED = Set.of("Where to start", "Conventions used in these pages");

  /** A Markdown link or image: optional {@code !}, the text, then the target. */
  private static final Pattern LINK = Pattern.compile("(!?)\\[([^\\]]*)\\]\\(([^)\\s]+)\\)");

  /** An inline code span, inside which links are left as they are. */
  private static final Pattern CODE_SPAN = Pattern.compile("`[^`\\n]*`");

  /** The starter READMEs that the index may link. */
  private static final Pattern STARTER_README = Pattern.compile("starters/([^/]+)/README\\.md");

  /** The committer of the wiki commits, fixed so the commit needs no git configuration. */
  private static final List<String> COMMITTER =
      List.of(
          "-c", "user.name=github-actions[bot]",
          "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com");

  /** The repository root that holds docs/ and starters/. */
  private final Path repository;

  /** The working tree of the wiki clone. */
  private final Path wiki;

  /** The page name of each copied source path, in index order. */
  private final Map<String, String> pageNames = new LinkedHashMap<>();

  /** The sidebar entries of each group, as Markdown list lines. */
  private final Map<String, List<String>> groupEntries = new LinkedHashMap<>();

  private WikiSync(Path repository, Path wiki) {
    this.repository = repository;
    this.wiki = wiki;
  }

  /**
   * Runs the sync and exits with 1 on any failed check.
   *
   * @param args the repository root, the wiki working tree and an optional {@code --commit}
   */
  public static void main(String[] args) {
    if (args.length < 2 || args.length > 3 || (args.length == 3 && !"--commit".equals(args[2]))) {
      System.err.println("usage: java WikiSync.java <repository> <wiki> [--commit]");
      System.exit(2);
    }
    try {
      var sync = new WikiSync(Path.of(args[0]), Path.of(args[1]));
      sync.run(args.length == 3);
    } catch (IllegalStateException | UncheckedIOException failure) {
      System.err.println("wiki-sync: error: " + failure.getMessage());
      System.exit(1);
    }
  }

  /** Builds every page in memory, checks the wiki, then writes, deletes and optionally commits. */
  private void run(boolean commit) {
    readIndex();
    var output = new LinkedHashMap<String, String>();
    pageNames.forEach((source, name) -> output.put(name + ".md", page(source)));
    List<String> existing = existingPages();
    existing.stream()
        .filter(file -> output.containsKey(file) || file.equals(SIDEBAR))
        .filter(file -> !file.equals(HOME + ".md") && !isGenerated(file))
        .findFirst()
        .ifPresent(
            file -> {
              throw new IllegalStateException(file + " in the wiki was not generated; rename it");
            });
    output.put(SIDEBAR, sidebar(existing));
    List<String> stale =
        existing.stream().filter(this::isGenerated).filter(file -> !output.containsKey(file)).toList();
    stale.forEach(file -> delete(wiki.resolve(file)));
    output.forEach((file, content) -> write(wiki.resolve(file), content));
    System.out.println("wiki-sync: " + output.size() + " pages written, " + stale.size() + " deleted");
    if (commit) {
      commitChanges();
    }
  }

  /** Reads the index: each table row of a known section names a page and its sidebar group. */
  private void readIndex() {
    pageNames.put(INDEX, HOME);
    pageNames.put(ROOT_README, ROOT_README_PAGE);
    String group = null;
    for (String line : read(INDEX).lines().toList()) {
      if (line.startsWith("## ")) {
        String section = line.substring(3).strip();
        if (!SKIPPED.contains(section) && !GROUPS.containsKey(section)) {
          throw new IllegalStateException(INDEX + ": section '" + section + "' has no wiki group");
        }
        group = GROUPS.get(section);
      } else if (group != null && line.startsWith("|")) {
        addRow(group, line);
      }
    }
  }

  /** Adds the page of every relative link of a table row to its group; a page already added is kept once. */
  private void addRow(String group, String row) {
    Matcher link = LINK.matcher(row);
    while (link.find()) {
      String target = link.group(3);
      if (target.contains("://") || target.startsWith("#")) {
        continue;
      }
      String source = resolve(INDEX, target.split("#", 2)[0]);
      if (pageNames.containsKey(source)) {
        continue;
      }
      String name = pageName(source);
      if (pageNames.containsValue(name) && !name.equals(pageNames.get(source))) {
        throw new IllegalStateException(source + " and another page both map to " + name);
      }
      pageNames.put(source, name);
      String label = firstHeading(source);
      groupEntries.computeIfAbsent(group, key -> new ArrayList<>()).add("- [" + label + "](" + name + ")");
    }
  }

  /**
   * Names a page from its path alone, so the name stays stable.
   *
   * @param source the repository path of the page
   * @return the wiki page name
   */
  private String pageName(String source) {
    Matcher starter = STARTER_README.matcher(source);
    if (starter.matches()) {
      return starter.group(1);
    }
    if (!source.startsWith("docs/") || !source.endsWith(".md")) {
      throw new IllegalStateException(INDEX + " links " + source + ", which is not a docs page");
    }
    String[] parts = source.substring(5, source.length() - 3).split("/");
    if (parts.length == 1) {
      return capitalize(parts[0]);
    }
    String folder = "adr".equals(parts[0]) ? "ADR" : capitalize(parts[0]);
    return folder + "-" + String.join("-", List.of(parts).subList(1, parts.length));
  }

  /** Returns the word with its first letter in upper case. */
  private String capitalize(String word) {
    return Character.toUpperCase(word.charAt(0)) + word.substring(1);
  }

  /** Returns the text of a page's first level-one heading, the page's sidebar label. */
  private String firstHeading(String source) {
    return read(source)
        .lines()
        .filter(line -> line.startsWith("# "))
        .findFirst()
        .map(line -> line.substring(2).strip())
        .orElseThrow(() -> new IllegalStateException(source + " has no level-one heading"));
  }

  /**
   * Returns a copied page: the marker, the text with its links rewritten, and the source note. Text
   * between fences is rewritten as one block, so a link whose text wraps onto the next line is found.
   */
  private String page(String source) {
    var body = new StringBuilder(MARKER).append(source).append(" -->\n");
    var text = new StringBuilder();
    String fence = null;
    for (String line : read(source).lines().toList()) {
      String opening = line.stripLeading();
      if (fence == null && (opening.startsWith("```") || opening.startsWith("~~~"))) {
        fence = opening.substring(0, 3);
        body.append(rewriteLinks(source, text.toString())).append(line).append('\n');
        text.setLength(0);
      } else if (fence != null) {
        fence = opening.startsWith(fence) ? null : fence;
        body.append(line).append('\n');
      } else {
        text.append(line).append('\n');
      }
    }
    return body.append(rewriteLinks(source, text.toString()))
        .append("\n---\n_Copied from [")
        .append(source)
        .append("](")
        .append(BLOB)
        .append(source)
        .append("); edit it there, not in the wiki._\n")
        .toString();
  }

  /** Rewrites the links of a block outside code spans; code spans inside link text are kept. */
  private String rewriteLinks(String source, String text) {
    List<int[]> spans = CODE_SPAN.matcher(text).results().map(span -> new int[] {span.start(), span.end()}).toList();
    Matcher link = LINK.matcher(text);
    var result = new StringBuilder();
    while (link.find()) {
      int start = link.start();
      boolean inCode = spans.stream().anyMatch(span -> start > span[0] && start < span[1]);
      String replacement = inCode ? link.group() : rewriteLink(source, link);
      link.appendReplacement(result, Matcher.quoteReplacement(replacement));
    }
    return link.appendTail(result).toString();
  }

  /** Points one link at a copied page, or at the file on GitHub; images and broken links fail. */
  private String rewriteLink(String source, Matcher link) {
    String target = link.group(3);
    if (target.startsWith("#") || target.contains("://") || target.startsWith("mailto:")) {
      return link.group();
    }
    if (!link.group(1).isEmpty()) {
      throw new IllegalStateException(source + ": relative image " + target + " is not supported");
    }
    String[] pathAndAnchor = target.split("#", 2);
    String anchor = pathAndAnchor.length == 2 ? "#" + pathAndAnchor[1] : "";
    String resolved = resolve(source, pathAndAnchor[0]);
    String name = pageNames.get(resolved);
    String destination = name != null ? name : BLOB + resolved;
    return "[" + link.group(2) + "](" + destination + anchor + ")";
  }

  /**
   * Resolves a relative link against its page and checks that it stays in the repository and exists.
   *
   * @param source the repository path of the page holding the link
   * @param target the link's path, without its anchor
   * @return the repository path the link points at
   */
  private String resolve(String source, String target) {
    Path resolved = Path.of(source).resolveSibling(target).normalize();
    if (resolved.startsWith("..")) {
      throw new IllegalStateException(source + ": link " + target + " leaves the repository");
    }
    if (!Files.exists(repository.resolve(resolved))) {
      throw new IllegalStateException(source + ": link " + target + " leads nowhere");
    }
    return resolved.toString().replace('\\', '/');
  }

  /** Returns the sidebar: Home, the index groups in the owner's order, then the audit reports. */
  private String sidebar(List<String> existing) {
    var text = new StringBuilder(MARKER).append(INDEX).append(" -->\n**[Home](Home)**\n");
    text.append("- [Project README](").append(ROOT_README_PAGE).append(")\n");
    GROUP_ORDER.forEach(
        group -> {
          text.append("\n**").append(group).append("**\n");
          groupEntries.getOrDefault(group, List.of()).forEach(entry -> text.append(entry).append('\n'));
        });
    text.append("\n**Audit reports**\n");
    existing.stream()
        .filter(file -> !file.startsWith("_") && !file.equals(HOME + ".md") && !isGenerated(file))
        .map(file -> file.substring(0, file.length() - 3))
        .forEach(name -> text.append("- [").append(name.replace('-', ' ')).append("](").append(name).append(")\n"));
    return text.toString();
  }

  /** Lists the Markdown pages at the top of the wiki, sorted by name. */
  private List<String> existingPages() {
    try (Stream<Path> files = Files.list(wiki)) {
      return files
          .map(file -> file.getFileName().toString())
          .filter(file -> file.endsWith(".md"))
          .sorted()
          .toList();
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot list " + wiki, failure);
    }
  }

  /** Tells whether a wiki page was written by this sync. */
  private boolean isGenerated(String file) {
    try (Stream<String> lines = Files.lines(wiki.resolve(file), StandardCharsets.UTF_8)) {
      return lines.findFirst().map(line -> line.startsWith(MARKER)).orElse(false);
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot read " + file, failure);
    }
  }

  /** Commits the wiki's changes, and makes no commit when nothing changed. */
  private void commitChanges() {
    git("add", "-A");
    if (git("status", "--porcelain").isBlank()) {
      System.out.println("wiki-sync: no change, no commit");
      return;
    }
    git("commit", "-q", "-m", "Sync the documentation from docs/ on main");
    System.out.println("wiki-sync: committed");
  }

  /** Runs git in the wiki with fixed arguments and returns its output; a non-zero exit fails. */
  private String git(String... arguments) {
    var command = new ArrayList<>(List.of("git", "-C", wiki.toString()));
    command.addAll(COMMITTER);
    command.addAll(List.of(arguments));
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.waitFor() != 0) {
        throw new IllegalStateException("git " + String.join(" ", arguments) + " failed: " + output);
      }
      return output;
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot run git", failure);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while running git", interrupted);
    }
  }

  /** Reads a repository file as UTF-8. */
  private String read(String source) {
    try {
      return Files.readString(repository.resolve(source), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot read " + source, failure);
    }
  }

  /** Writes a wiki page as UTF-8. */
  private void write(Path file, String content) {
    try {
      Files.writeString(file, content, StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot write " + file, failure);
    }
  }

  /** Deletes a stale generated page. */
  private void delete(Path file) {
    try {
      Files.delete(file);
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot delete " + file, failure);
    }
  }
}
