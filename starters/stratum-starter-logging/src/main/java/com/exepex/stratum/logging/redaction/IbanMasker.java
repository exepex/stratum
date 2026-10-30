package com.exepex.stratum.logging.redaction;

import com.exepex.stratum.logging.redaction.MaskedText.Replacement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;
import org.apache.commons.validator.routines.IBANValidator;

/**
 * Finds IBANs in text, to be masked keeping their last four characters: a country code, check
 * digits and 10 to 30 letters or digits, with optional spaces, that Apache Commons Validator
 * accepts as a valid IBAN for that country.
 */
@UtilityClass
class IbanMasker {

  /** A possible IBAN, with optional spaces between groups. */
  private static final Pattern IBAN =
      Pattern.compile("(?i)\\b[a-z]{2}\\d{2}(?:\\s?[a-z0-9]){10,30}\\b");

  /**
   * Returns every valid IBAN, each keeping its last four characters. The pattern can take in words
   * after an IBAN, including the start of the next one, so the search goes on after the IBAN
   * itself.
   *
   * @param text the text to search
   * @return the IBANs to mask, in order
   */
  static List<Replacement> ibansIn(String text) {
    var candidates = IBAN.matcher(text);
    var maskedIbans = new ArrayList<Replacement>();
    var searchFrom = 0;
    // A loop, because after a candidate that holds no IBAN the search goes on from its second
    // word, where an IBAN may start after an IBAN-shaped word.
    while (candidates.find(searchFrom)) {
      var candidate = candidates.toMatchResult();
      var maskedIban = validIbanAtStart(candidate);
      maskedIban.ifPresent(maskedIbans::add);
      searchFrom = maskedIban.isPresent() ? maskedIban.get().end() : secondWordStart(candidate);
    }
    return maskedIbans;
  }

  /** Returns where a candidate's second word starts, or its end if it is one word. */
  private static int secondWordStart(MatchResult candidate) {
    var firstWordLength =
        (int)
            candidate
                .group()
                .chars()
                .takeWhile(character -> !Character.isWhitespace(character))
                .count();
    return firstWordLength < candidate.group().length()
        ? candidate.start() + firstWordLength + 1
        : candidate.end();
  }

  /**
   * Returns the masked IBAN at the start of a candidate. Words are dropped from the candidate's end
   * until what is left is a valid IBAN. Only the words that give its country's IBAN length are
   * checked, and a candidate whose first letters are no IBAN country is rejected at once.
   */
  private static Optional<Replacement> validIbanAtStart(MatchResult candidate) {
    var ibanValidator = IBANValidator.getInstance();
    return Optional.ofNullable(ibanValidator.getValidator(ibanWithoutSpaces(candidate.group())))
        .flatMap(
            countryIbanFormat ->
                Stream.iterate(
                        candidate.group(), words -> !words.isEmpty(), IbanMasker::withoutLastWord)
                    .filter(
                        words ->
                            ibanWithoutSpaces(words).length() == countryIbanFormat.getIbanLength())
                    .filter(words -> ibanValidator.isValid(ibanWithoutSpaces(words)))
                    .findFirst())
        .map(
            iban ->
                MaskedText.keepingLastFourAt(
                    candidate.start(), candidate.start() + iban.length(), ibanWithoutSpaces(iban)));
  }

  /** Returns the words without the last one, or an empty string if there is only one. */
  private static String withoutLastWord(String words) {
    return words.substring(0, Math.max(words.lastIndexOf(' '), 0));
  }

  /** Returns an IBAN without spaces, in upper case. */
  private static String ibanWithoutSpaces(String iban) {
    return iban.replace(" ", "").toUpperCase(Locale.ROOT);
  }
}
