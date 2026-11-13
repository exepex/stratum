import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that a link's {@code #anchor} names a heading of its target Markdown page, with the anchors
 * GitHub gives headings: the text in lower case, punctuation other than {@code _} and {@code -}
 * removed, spaces as hyphens, and {@code -1}, {@code -2} on repeats.
 */
final class HeadingAnchors {

  /** A level-one to level-six heading line, with its text. */
  private static final Pattern HEADING = Pattern.compile("#{1,6}[ \\t]+(.*)");

  /** The characters an anchor drops: all but letters, digits, marks, underscores, hyphens and spaces. */
  private static final Pattern DROPPED = Pattern.compile("[^\\p{L}\\p{N}\\p{M}_\\- ]");

  /** Reads a repository file by its path. */
  private final Function<String, String> reader;

  /** The anchors of each page read so far, so each page is read once. */
  private final Map<String, Set<String>> anchorsByPage = new HashMap<>();

  /**
   * Creates the check.
   *
   * @param reader reads a repository file by its path
   */
  HeadingAnchors(Function<String, String> reader) {
    this.reader = reader;
  }

  /**
   * Fails when the anchor names no heading of a Markdown target; other targets, such as code files,
   * are not checked.
   *
   * @param source the repository path of the page holding the link
   * @param target the repository path the link points at, the source itself for {@code #anchor}
   * @param anchor the link's anchor, without {@code #}
   */
  void check(String source, String target, String anchor) {
    if (target.endsWith(".md") && !anchorsByPage.computeIfAbsent(target, this::anchorsOf).contains(anchor)) {
      throw new IllegalStateException(source + ": anchor #" + anchor + " names no heading in " + target);
    }
  }

  /** Returns the anchors of every heading of a page outside code fences. */
  private Set<String> anchorsOf(String page) {
    var fence = new CodeFence();
    var repeats = new HashMap<String, Integer>();
    var anchors = new HashSet<String>();
    // A loop, not a stream: whether a line is fenced depends on the lines before it.
    for (String line : reader.apply(page).lines().toList()) {
      Matcher heading = HEADING.matcher(line);
      if (!fence.isFenced(line) && heading.matches()) {
        String anchor = anchorOf(heading.group(1).strip());
        int repeat = repeats.merge(anchor, 1, Integer::sum) - 1;
        anchors.add(repeat == 0 ? anchor : anchor + "-" + repeat);
      }
    }
    return anchors;
  }

  /** Returns GitHub's anchor for a heading's text. */
  private String anchorOf(String heading) {
    return DROPPED.matcher(heading.toLowerCase(Locale.ROOT)).replaceAll("").replace(' ', '-');
  }
}
