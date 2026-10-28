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
import lombok.experimental.UtilityClass;
import org.apache.commons.validator.routines.CreditCardValidator;
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
              + "(?:[A-Za-z]++=[^\\r\\n]++|[A-Za-z0-9+/._=~-]{4,})");

  /** A user name and password in a URL, such as {@code https://user:secret@host}. */
  private static final Pattern URL_CREDENTIALS =
      Pattern.compile("(?i)(?<![a-z0-9+.-])([a-z][a-z0-9+.-]*+://)[^\\s/:@]++:[^\\s/@]++@");

  /**
   * Digit groups separated by single spaces or dashes, with at least 13 digits in all. A run must
   * start where no word or earlier group comes before it, so that matching stays linear.
   */
  private static final Pattern CARD_GROUPS =
      Pattern.compile("(?<!\\w|\\d[ -])\\d(?:[ -]?\\d){12,}+");

  /** One group of digits. */
  private static final Pattern DIGIT_GROUP = Pattern.compile("\\d+");

  /** The fewest digits in a card number. */
  private static final int MIN_CARD_DIGITS = 13;

  /** The most digits in a card number. */
  private static final int MAX_CARD_DIGITS = 19;

  /** Checks a card number's length and Luhn check digit, for any card brand. */
  private static final CreditCardValidator CARD =
      CreditCardValidator.genericCreditCardValidator(MIN_CARD_DIGITS, MAX_CARD_DIGITS);

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
            rule(text -> text.contains("eyJ"), JWT, match -> Redactor.MASK),
            rule(text -> text.contains(" "), AUTH_SCHEME, ContentRules::maskAuthScheme),
            rule(text -> text.contains("://"), URL_CREDENTIALS, ContentRules::maskUrlCredentials),
            new KeyValuePairs(fieldNames),
            rule(ContentRules::hasDigit, CARD_GROUPS, match -> maskCards(match.group())),
            rule(text -> text.contains("-"), SSN, match -> Redactor.keepingLastFour(match.group())),
            rule(text -> text.contains("@"), EMAIL, ContentRules::maskEmail),
            rule(ContentRules::hasDigit, IBAN, ContentRules::maskIban),
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

  /**
   * Masks the card numbers in a run of digit groups, keeping the last four digits. A run such as
   * {@code 4111 1111 1111 1111 43} can hold a card next to other numbers, so the longest valid card
   * that starts at each group is looked for, and the search goes on after the card it finds.
   */
  private static String maskCards(String run) {
    var groups = DIGIT_GROUP.matcher(run).results().toList();
    var cards =
        Stream.iterate(
                nextCard(groups, 0),
                Optional::isPresent,
                previousCard -> nextCard(groups, previousCard.orElseThrow().lastGroup() + 1))
            .map(Optional::orElseThrow)
            .map(
                card ->
                    new Replacement(
                        card.start(), card.end(), Redactor.keepingLastFour(card.digits())))
            .toList();
    return replace(run, cards);
  }

  /** Returns the first valid card that starts at or after the group, if there is one. */
  private static Optional<CardSpan> nextCard(List<MatchResult> groups, int fromGroup) {
    return IntStream.range(fromGroup, groups.size())
        .mapToObj(firstGroup -> longestCard(groups, firstGroup))
        .flatMap(Optional::stream)
        .findFirst();
  }

  /** Returns the longest valid card that starts at the first group, if there is one. */
  private static Optional<CardSpan> longestCard(List<MatchResult> groups, int firstGroup) {
    var start = groups.get(firstGroup).start();
    return IntStream.range(firstGroup, groups.size())
        .mapToObj(
            lastGroup ->
                new CardSpan(
                    start,
                    groups.get(lastGroup).end(),
                    lastGroup,
                    digitsOf(groups.subList(firstGroup, lastGroup + 1))))
        .takeWhile(candidate -> candidate.digits().length() <= MAX_CARD_DIGITS)
        .filter(candidate -> candidate.digits().length() >= MIN_CARD_DIGITS)
        .filter(candidate -> CARD.isValid(candidate.digits()))
        .reduce((shorter, longer) -> longer);
  }

  /** Returns the digits of the groups, without the spaces or dashes between them. */
  private static String digitsOf(List<MatchResult> groups) {
    return groups.stream().map(MatchResult::group).collect(Collectors.joining());
  }

  /** Masks a valid email address, keeping its last four characters. */
  private static String maskEmail(MatchResult match) {
    return EmailValidator.getInstance().isValid(match.group())
        ? Redactor.keepingLastFour(match.group())
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
        .map(iban -> Redactor.keepingLastFour(compact(iban)) + found.substring(iban.length()))
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
    var replacements =
        StreamSupport.stream(numbers.spliterator(), false)
            .map(
                number ->
                    new Replacement(
                        number.start(), number.end(), Redactor.keepingLastFour(number.rawString())))
            .toList();
    return replace(text, replacements);
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
   * Where a possible card is in a run of digit groups.
   *
   * @param start where its first group starts
   * @param end where its last group ends
   * @param lastGroup the position of its last group in the run
   * @param digits its digits, without spaces or dashes
   */
  private record CardSpan(int start, int end, int lastGroup, String digits) {}

  /**
   * A part of a text and what is written in its place.
   *
   * @param start where the part starts
   * @param end where the part ends
   * @param text what is written instead
   */
  record Replacement(int start, int end, String text) {}
}
