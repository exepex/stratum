package com.exepex.stratum.data;

import java.util.List;
import java.util.Locale;

/**
 * The operation a statement's SQL starts with, such as {@code SELECT}, for its span: the first word
 * after white space and comments, when it is a known SQL keyword. It reads nothing after that word,
 * so no table name or value can reach the span.
 */
enum QueryOperation {

  /** A query. */
  SELECT,
  /** An insert. */
  INSERT,
  /** An update. */
  UPDATE,
  /** A delete. */
  DELETE,
  /** A merge or upsert. */
  MERGE,
  /** A statement with common table expressions. */
  WITH,
  /** A stored procedure call. */
  CALL,
  /** A schema object created. */
  CREATE,
  /** A schema object changed. */
  ALTER,
  /** A schema object dropped. */
  DROP,
  /** A table emptied. */
  TRUNCATE,
  /** A transaction started. */
  BEGIN,
  /** A transaction committed. */
  COMMIT,
  /** A transaction rolled back. */
  ROLLBACK,
  /** A session setting changed. */
  SET,
  /** A session setting read. */
  SHOW,
  /** A list of rows. */
  VALUES,
  /** A table locked. */
  LOCK,
  /** A bulk copy. */
  COPY,
  /** A query plan read. */
  EXPLAIN,
  /** A statement whose first word is no known keyword. */
  UNKNOWN,
  /** A plain batch whose statements start with different operations. */
  BATCH;

  /** The longest keyword, so a longer first word is never copied. */
  private static final int LONGEST = 8;

  /**
   * Returns the operation the SQL starts with.
   *
   * @param sql the statement's SQL
   * @return its first keyword, or {@link #UNKNOWN}; never {@link #BATCH}
   */
  static QueryOperation of(String sql) {
    int start = firstWord(sql);
    int end = start;
    while (end < sql.length() && end - start <= LONGEST && Character.isLetter(sql.charAt(end))) {
      end++;
    }
    if (end == start || end - start > LONGEST) {
      return UNKNOWN;
    }
    return keyword(sql.substring(start, end).toUpperCase(Locale.ROOT));
  }

  /**
   * Returns the operation of a plain batch: that of its first SQL, or {@link #BATCH} when another
   * starts with a different one.
   *
   * @param batch the SQL added to the batch, never empty
   * @return the batch's operation
   */
  static QueryOperation ofBatch(List<String> batch) {
    var first = batch.getFirst();
    var operation = of(first);
    for (var sql : batch) {
      if (!sql.equals(first) && of(sql) != operation) {
        return BATCH;
      }
    }
    return operation;
  }

  /** Returns the keyword a word names, or {@link #UNKNOWN}; never {@link #BATCH}. */
  private static QueryOperation keyword(String word) {
    if (BATCH.name().equals(word)) {
      return UNKNOWN;
    }
    try {
      return valueOf(word);
    } catch (IllegalArgumentException _) {
      return UNKNOWN;
    }
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
