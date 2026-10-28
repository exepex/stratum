package com.exepex.stratum.logging.redaction;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.Leniency;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.experimental.UtilityClass;
import org.apache.commons.validator.routines.EmailValidator;
import org.apache.commons.validator.routines.IBANValidator;

/**
 * The rules that mask secrets and personal data by what the text looks like. A pattern only finds
 * candidates; Apache Commons Validator and Google's libphonenumber decide whether a candidate is a
 * real email address, IBAN or phone number, so that ordinary numbers stay readable.
 *
 * <p>The JWT and email patterns must start a word and match possessively, so that matching stays
 * linear on long hostile text.
 */
@UtilityClass
class ContentRules {

  /** A JSON Web Token: two or three Base64 parts, the first starting with {@code eyJ}. */
  private static final Pattern JWT =
      Pattern.compile("(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{5,}+\\.[A-Za-z0-9_.-]{5,}+");

  /** An HTTP authorization scheme and its credentials, such as {@code Bearer abc}. */
  private static final Pattern AUTH_SCHEME =
      Pattern.compile("(?i)\\b(basic|bearer|digest|negotiate)\\s+[A-Za-z0-9+/._=~-]{4,}");

  /** A user name and password in a URL, such as {@code https://user:secret@host}. */
  private static final Pattern URL_CREDENTIALS =
      Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://)[^\\s/:@]+:[^\\s/@]+@");

  /** A US social security number. */
  private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

  /** A possible email address. */
  private static final Pattern EMAIL =
      Pattern.compile("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]++@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

  /** A possible IBAN, with optional spaces between groups. */
  private static final Pattern IBAN =
      Pattern.compile("(?i)\\b[a-z]{2}\\d{2}(?:\\s?[a-z0-9]){10,30}\\b");

  /** Google's phone number library, which knows every country's numbering plan. */
  private static final PhoneNumberUtil PHONE_NUMBERS = PhoneNumberUtil.getInstance();

  /**
   * Returns all the rules as one: secrets first, then personal data, then the service's own.
   *
   * @param fieldNames the names whose values a key-value pair masks
   * @param extraPatterns the service's own patterns, masked in full
   * @return a function that applies every rule in turn
   */
  UnaryOperator<String> of(SensitiveFieldNames fieldNames, List<Pattern> extraPatterns) {
    var builtIn =
        Stream.<UnaryOperator<String>>of(
            rule(text -> text.contains("eyJ"), JWT, match -> Masks.MASK),
            rule(text -> text.contains(" "), AUTH_SCHEME, ContentRules::maskAuthScheme),
            rule(text -> text.contains("://"), URL_CREDENTIALS, ContentRules::maskUrlCredentials),
            new KeyValuePairs(fieldNames),
            new CardNumbers(),
            rule(text -> text.contains("-"), SSN, match -> Masks.keepingLastFour(match.group())),
            rule(text -> text.contains("@"), EMAIL, ContentRules::maskEmail),
            rule(ContentRules::hasDigit, IBAN, ContentRules::maskIban),
            ContentRules::maskPhoneNumbers);
    var extra =
        extraPatterns.stream().map(pattern -> rule(text -> true, pattern, match -> Masks.MASK));
    return Stream.concat(builtIn, extra)
        .reduce(
            UnaryOperator.identity(), (earlier, later) -> text -> later.apply(earlier.apply(text)));
  }

  /**
   * Returns a rule that masks every match of the pattern. A cheap check skips text the pattern
   * cannot match, and the masked form is written as it is, never read as a group reference.
   */
  private static UnaryOperator<String> rule(
      Predicate<String> mayMatch, Pattern pattern, Function<MatchResult, String> mask) {
    return text ->
        mayMatch.test(text)
            ? pattern.matcher(text).replaceAll(mask.andThen(Matcher::quoteReplacement))
            : text;
  }

  /** Masks the credentials after an authorization scheme, keeping the scheme. */
  private static String maskAuthScheme(MatchResult match) {
    return match.group(1) + " " + Masks.MASK;
  }

  /** Masks the user name and password of a URL, keeping its scheme. */
  private static String maskUrlCredentials(MatchResult match) {
    return match.group(1) + Masks.MASK + "@";
  }

  /** Masks a valid email address, keeping its last four characters. */
  private static String maskEmail(MatchResult match) {
    return EmailValidator.getInstance().isValid(match.group())
        ? Masks.keepingLastFour(match.group())
        : match.group();
  }

  /**
   * Masks a valid IBAN, keeping its last four characters. The pattern can take in words after the
   * IBAN, so words are dropped from the end until what is left is valid.
   */
  private static String maskIban(MatchResult match) {
    var found = match.group();
    return Stream.iterate(
            found,
            candidate -> !candidate.isEmpty(),
            candidate -> candidate.substring(0, Math.max(candidate.lastIndexOf(' '), 0)))
        .filter(candidate -> IBANValidator.getInstance().isValid(compact(candidate)))
        .findFirst()
        .map(iban -> Masks.keepingLastFour(compact(iban)) + found.substring(iban.length()))
        .orElse(found);
  }

  /** Returns an IBAN without spaces, in upper case. */
  private static String compact(String iban) {
    return iban.replace(" ", "").toUpperCase(Locale.ROOT);
  }

  /**
   * Masks international phone numbers, keeping the last four digits. Only numbers with a country
   * code, such as {@code +44 20 7946 0958}, are found, so text without a {@code +} skips the
   * search.
   */
  private static String maskPhoneNumbers(String text) {
    if (!text.contains("+")) {
      return text;
    }
    var numbers = PHONE_NUMBERS.findNumbers(text, null, Leniency.VALID, Long.MAX_VALUE);
    var masked = new StringBuilder(text);
    // From the last number to the first, so that the earlier positions stay correct.
    StreamSupport.stream(numbers.spliterator(), false)
        .toList()
        .reversed()
        .forEach(
            number ->
                masked.replace(
                    number.start(), number.end(), Masks.keepingLastFour(number.rawString())));
    return masked.toString();
  }

  /** Returns whether the text holds a digit. */
  private static boolean hasDigit(String text) {
    return text.chars().anyMatch(Character::isDigit);
  }
}
