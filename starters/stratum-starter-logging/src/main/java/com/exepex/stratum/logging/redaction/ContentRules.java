package com.exepex.stratum.logging.redaction;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.Leniency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.Builder;
import lombok.experimental.UtilityClass;
import org.apache.commons.validator.routines.EmailValidator;
import org.apache.commons.validator.routines.IBANValidator;

/**
 * The rules that mask secrets and personal data by what the text looks like. A pattern only finds
 * candidates; Apache Commons Validator and Google's libphonenumber decide whether a candidate is a
 * real card number, email address, IBAN or phone number, so that ordinary numbers stay readable.
 *
 * <p>The patterns that can match long runs must start a run, through a lookbehind, and match
 * possessively, so that matching stays linear on long hostile text.
 */
@UtilityClass
class ContentRules {

  /** A JSON Web Token: two or three Base64 parts, the first starting with {@code eyJ}. */
  private static final Pattern JWT =
      Pattern.compile("(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{5,}+\\.[A-Za-z0-9_.-]{5,}+");

  /**
   * An HTTP authorization scheme and its credentials: a token, such as {@code Bearer abc}, or a
   * list of parameters, such as Digest's {@code username="x", response="y"}, which runs to the end
   * of the line.
   */
  private static final Pattern AUTH_SCHEME =
      Pattern.compile(
          "(?i)\\b(basic|bearer|digest|negotiate)\\s+"
              + "(?:[a-z]++=[^\\r\\n]++|[a-z0-9+/._=~-]{4,})");

  /**
   * A password in a URL, with or without a user name, such as {@code https://user:secret@host} or
   * {@code redis://:secret@host}.
   */
  private static final Pattern URL_CREDENTIALS =
      Pattern.compile("(?i)(?<![a-z0-9+.-])([a-z][a-z0-9+.-]*+://)[^\\s/:@]*+:[^\\s/@]++@");

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

  /** How many characters an identifier such as a card number keeps visible at its end. */
  private static final int VISIBLE_END = 4;

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
            rule(text -> text.contains("eyJ"), JWT, match -> Redactor.MASK),
            rule(text -> text.contains(" "), AUTH_SCHEME, ContentRules::maskAuthScheme),
            rule(text -> text.contains("://"), URL_CREDENTIALS, ContentRules::maskUrlCredentials),
            new KeyValuePairs(fieldNames),
            rule(
                ContentRules::hasDigit,
                CardNumbers.CARD_GROUPS,
                match -> CardNumbers.mask(match.group())),
            rule(text -> text.contains("-"), SSN, match -> keepingLastFour(match.group())),
            rule(text -> text.contains("@"), EMAIL, ContentRules::maskEmail),
            text -> hasDigit(text) ? maskIbans(text) : text,
            ContentRules::maskPhoneNumbers);
    var extra =
        extraPatterns.stream().map(pattern -> rule(text -> true, pattern, match -> Redactor.MASK));
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
    return match.group(1) + " " + Redactor.MASK;
  }

  /** Masks the user name and password of a URL, keeping its scheme. */
  private static String maskUrlCredentials(MatchResult match) {
    return match.group(1) + Redactor.MASK + "@";
  }

  /** Masks a valid email address, keeping its last four characters. */
  private static String maskEmail(MatchResult match) {
    return EmailValidator.getInstance().isValid(match.group())
        ? keepingLastFour(match.group())
        : match.group();
  }

  /**
   * Masks every valid IBAN, keeping its last four characters. The pattern can take in words after
   * an IBAN, including the start of the next one, so the search goes on after the IBAN itself.
   */
  private static String maskIbans(String text) {
    var matcher = IBAN.matcher(text);
    var ibans =
        Stream.iterate(
                nextIban(matcher, 0),
                Optional::isPresent,
                found -> nextIban(matcher, found.orElseThrow().end()))
            .map(Optional::orElseThrow)
            .toList();
    return replace(text, ibans);
  }

  /**
   * Finds the next valid IBAN from the given position. After a candidate that is not one, the
   * search goes on from its second word, where an IBAN may start after an IBAN-shaped word.
   */
  private static Optional<Replacement> nextIban(Matcher matcher, int from) {
    return Stream.iterate(
            find(matcher, from),
            Optional::isPresent,
            candidate -> find(matcher, secondWord(candidate.orElseThrow())))
        .map(Optional::orElseThrow)
        .flatMap(candidate -> validIban(candidate).stream())
        .findFirst();
  }

  /** Returns where a candidate's second word starts, or its end if it is one word. */
  private static int secondWord(MatchResult candidate) {
    var firstWord =
        candidate
            .group()
            .chars()
            .takeWhile(character -> !Character.isWhitespace(character))
            .count();
    return firstWord < candidate.group().length()
        ? candidate.start() + (int) firstWord + 1
        : candidate.end();
  }

  /** Returns the pattern's next match from the given position, if there is one. */
  private static Optional<MatchResult> find(Matcher matcher, int from) {
    return matcher.find(from) ? Optional.of(matcher.toMatchResult()) : Optional.empty();
  }

  /**
   * Returns the masked IBAN at the start of a candidate. Words are dropped from the candidate's end
   * until what is left is a valid IBAN. Only the words that give its country's IBAN length are
   * checked, and a candidate whose first letters are no IBAN country is rejected at once.
   */
  private static Optional<Replacement> validIban(MatchResult candidate) {
    var ibans = IBANValidator.getInstance();
    return Optional.ofNullable(ibans.getValidator(compact(candidate.group())))
        .flatMap(
            country ->
                Stream.iterate(
                        candidate.group(),
                        words -> !words.isEmpty(),
                        words -> words.substring(0, Math.max(words.lastIndexOf(' '), 0)))
                    .filter(words -> compact(words).length() == country.getIbanLength())
                    .filter(words -> ibans.isValid(compact(words)))
                    .findFirst())
        .map(
            iban ->
                Replacement.builder()
                    .start(candidate.start())
                    .end(candidate.start() + iban.length())
                    .text(keepingLastFour(compact(iban)))
                    .build());
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
    var replacements =
        StreamSupport.stream(numbers.spliterator(), false)
            .map(
                number ->
                    Replacement.builder()
                        .start(number.start())
                        .end(number.end())
                        .text(keepingLastFour(number.rawString()))
                        .build())
            .toList();
    return replace(text, replacements);
  }

  /**
   * Masks a value but keeps its last four characters, such as {@code ****1111} for a card; a value
   * of four characters or fewer is masked in full.
   *
   * @param value the value to mask
   * @return the masked value
   */
  static String keepingLastFour(String value) {
    return value.length() <= VISIBLE_END
        ? Redactor.MASK
        : Redactor.MASK + value.substring(value.length() - VISIBLE_END);
  }

  /**
   * Returns the text with each replacement written in place of the part it covers.
   *
   * @param text the text
   * @param replacements the parts to replace, in order and not overlapping
   * @return the text with the parts replaced
   */
  static String replace(String text, List<Replacement> replacements) {
    var tail = text.substring(replacements.isEmpty() ? 0 : replacements.getLast().end());
    return IntStream.range(0, replacements.size())
        .mapToObj(
            position ->
                text.substring(
                        position == 0 ? 0 : replacements.get(position - 1).end(),
                        replacements.get(position).start())
                    + replacements.get(position).text())
        .collect(Collectors.joining("", "", tail));
  }

  /** Returns whether the text holds a digit. */
  private static boolean hasDigit(String text) {
    return text.chars().anyMatch(Character::isDigit);
  }

  /**
   * A part of a text and what is written in its place.
   *
   * @param start where the part starts
   * @param end where the part ends
   * @param text what is written instead
   */
  @Builder
  record Replacement(int start, int end, String text) {}
}
