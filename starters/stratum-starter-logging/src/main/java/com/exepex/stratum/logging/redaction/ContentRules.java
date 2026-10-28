package com.exepex.stratum.logging.redaction;

import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;
import org.apache.commons.validator.routines.EmailValidator;
import org.apache.commons.validator.routines.IBANValidator;

/**
 * The rules that mask secrets and personal data by what the text looks like. Card numbers, IBANs
 * and email addresses found by their shape are checked with Apache Commons Validator first, so that
 * an ordinary number of the same length stays readable.
 */
@UtilityClass
class ContentRules {

  /**
   * A JSON Web Token: three Base64 parts, the first starting with {@code eyJ}. It must start a word
   * and its parts are matched possessively, so that matching stays linear on long hostile text.
   */
  private static final Pattern JWT =
      Pattern.compile(
          "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{5,}+\\.[A-Za-z0-9_-]{5,}+\\.[A-Za-z0-9_-]{5,}+");

  /** An HTTP authorization scheme and its credentials, such as {@code Bearer abc}. */
  private static final Pattern AUTH_SCHEME =
      Pattern.compile("(?i)\\b(basic|bearer|digest|negotiate)\\s+[A-Za-z0-9+/._=~-]{4,}");

  /** A user name and password in a URL, such as {@code https://user:secret@host}. */
  private static final Pattern URL_CREDENTIALS =
      Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://)[^\\s/:@]+:[^\\s/@]+@");

  /** A US social security number. */
  private static final Pattern SSN_SHAPE = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

  /**
   * A possible email address. Its local part must start a word and is matched possessively, so that
   * matching stays linear on a long run of letters with no domain.
   */
  private static final Pattern EMAIL_SHAPE =
      Pattern.compile("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]++@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

  /** A possible IBAN, with optional spaces between groups. */
  private static final Pattern IBAN_SHAPE =
      Pattern.compile("(?i)\\b[a-z]{2}\\d{2}(?:\\s?[a-z0-9]){10,30}\\b");

  /**
   * Returns the rules in the order they run: secrets, then personal data, then the service's own.
   *
   * @param fieldNames the names whose values a key-value pair masks
   * @param extraPatterns the service's own patterns, masked in full
   * @return the rules
   */
  List<UnaryOperator<String>> of(SensitiveFieldNames fieldNames, List<Pattern> extraPatterns) {
    var builtIn =
        Stream.<UnaryOperator<String>>of(
            new RedactionRule(text -> text.contains("eyJ"), JWT, match -> Masks.MASK),
            new RedactionRule(
                text -> text.indexOf(' ') >= 0,
                AUTH_SCHEME,
                match -> match.group(1) + " " + Masks.MASK),
            new RedactionRule(
                text -> text.contains("://"),
                URL_CREDENTIALS,
                match -> match.group(1) + Masks.MASK + "@"),
            new KeyValuePairs(fieldNames),
            new CardNumbers(),
            new RedactionRule(
                text -> text.indexOf('-') >= 0,
                SSN_SHAPE,
                match -> Masks.keepingLastFour(match.group())),
            new RedactionRule(text -> text.indexOf('@') >= 0, EMAIL_SHAPE, ContentRules::maskEmail),
            new RedactionRule(ContentRules::hasDigit, IBAN_SHAPE, ContentRules::maskIban));
    var extra =
        extraPatterns.stream()
            .<UnaryOperator<String>>map(
                pattern -> new RedactionRule(text -> true, pattern, match -> Masks.MASK));
    return Stream.concat(builtIn, extra).toList();
  }

  /** Masks a valid email address, keeping its last four characters. */
  private static String maskEmail(MatchResult match) {
    return EmailValidator.getInstance().isValid(match.group())
        ? Masks.keepingLastFour(match.group())
        : match.group();
  }

  /**
   * Masks a valid IBAN, keeping its last four characters. The shape can take in words after the
   * IBAN, so words are dropped from the end until what is left is valid.
   */
  private static String maskIban(MatchResult match) {
    var found = match.group();
    var candidate = found;
    while (!candidate.isEmpty()) {
      var compact = candidate.replace(" ", "").toUpperCase(Locale.ROOT);
      if (IBANValidator.getInstance().isValid(compact)) {
        return Masks.keepingLastFour(compact) + found.substring(candidate.length());
      }
      candidate = candidate.substring(0, Math.max(candidate.lastIndexOf(' '), 0));
    }
    return found;
  }

  /** Returns whether the text holds a digit. */
  private static boolean hasDigit(String text) {
    return text.chars().anyMatch(Character::isDigit);
  }
}
