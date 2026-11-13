/**
 * Tells which lines of a Markdown page belong to a code fence, where no line is a heading or a link.
 * One instance reads one page, line by line in order.
 */
final class CodeFence {

  /** The marker of the open fence, {@code ```} or {@code ~~~}, or null outside a fence. */
  private String open;

  /**
   * Tells whether a line opens a fence, closes it or lies inside it.
   *
   * @param line the next line of the page
   * @return true for a fence line or a line inside a fence
   */
  boolean isFenced(String line) {
    String opening = line.stripLeading();
    if (open != null) {
      open = opening.startsWith(open) ? null : open;
      return true;
    }
    if (opening.startsWith("```") || opening.startsWith("~~~")) {
      open = opening.substring(0, 3);
      return true;
    }
    return false;
  }
}
