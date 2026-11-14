package com.exepex.stratum.data;

import java.util.Locale;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Reads the operation a statement's SQL starts with, such as {@code SELECT}, for its span: the
 * first word after white space and comments, when it is a known SQL keyword. It reads nothing after
 * that word, so no table name or value can reach the span.
 */
@UtilityClass
class QueryOperation {

  /** The operation of a statement whose first word is no known keyword. */
  static final String UNKNOWN = "UNKNOWN";

  /** The operation of a plain batch whose statements start with different keywords. */
  static final String BATCH = "BATCH";

  /** The keywords a span may carry; any other first word is {@link #UNKNOWN}. */
  private static final Set<String> KEYWORDS =
      Set.of(
          "SELECT",
          "INSERT",
          "UPDATE",
          "DELETE",
          "MERGE",
          "WITH",
          "CALL",
          "CREATE",
          "ALTER",
          "DROP",
          "TRUNCATE",
          "BEGIN",
          "COMMIT",
          "ROLLBACK",
          "SET",
          "SHOW",
          "VALUES",
          "LOCK",
          "COPY",
          "EXPLAIN");

  /** The longest keyword, so a longer first word is never copied. */
  private static final int LONGEST = 8;

  /**
   * Returns the operation the SQL starts with.
   *
   * @param sql the statement's SQL
   * @return its first keyword in upper case, one of a fixed set, or {@link #UNKNOWN}
   */
  static String of(String sql) {
    int start = firstWord(sql);
    int end = start;
    while (end < sql.length() && end - start <= LONGEST && Character.isLetter(sql.charAt(end))) {
      end++;
    }
    if (end == start || end - start > LONGEST) {
      return UNKNOWN;
    }
    var word = sql.substring(start, end).toUpperCase(Locale.ROOT);
    return KEYWORDS.contains(word) ? word : UNKNOWN;
  }

  /** Returns where the first word starts, after white space, line comments and block comments. */
  private static int firstWord(String sql) {
    int at = 0;
    while (at < sql.length()) {
      if (Character.isWhitespace(sql.charAt(at))) {
        at++;
      } else if (sql.startsWith("--", at)) {
        at = lineEnd(sql, at);
      } else if (sql.startsWith("/*", at)) {
        at = commentEnd(sql, at);
      } else {
        return at;
      }
    }
    return at;
  }

  /** Returns the index after a line comment. */
  private static int lineEnd(String sql, int from) {
    int newline = sql.indexOf('\n', from);
    return newline < 0 ? sql.length() : newline + 1;
  }

  /** Returns the index after a block comment, or the end of an unterminated one. */
  private static int commentEnd(String sql, int from) {
    int close = sql.indexOf("*/", from + 2);
    return close < 0 ? sql.length() : close + 2;
  }
}
