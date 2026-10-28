package com.exepex.stratum.logging.redaction;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One rule that masks what a text pattern finds.
 *
 * @param mayMatch a cheap check that skips the pattern for text it cannot match
 * @param pattern what to find
 * @param mask turns each match into its masked form
 */
record RedactionRule(
    Predicate<String> mayMatch, Pattern pattern, Function<MatchResult, String> mask)
    implements UnaryOperator<String> {

  /**
   * Masks every match in the text. The masked form is written as it is: a dollar sign or backslash
   * in it is not read as a group reference.
   *
   * @param text the text to mask
   * @return the text with each match masked
   */
  @Override
  public String apply(String text) {
    return mayMatch.test(text)
        ? pattern.matcher(text).replaceAll(mask.andThen(Matcher::quoteReplacement))
        : text;
  }
}
