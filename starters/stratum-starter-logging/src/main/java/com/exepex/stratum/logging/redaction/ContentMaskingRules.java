package com.exepex.stratum.logging.redaction;

import com.exepex.stratum.logging.redaction.MaskedText.Replacement;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.Leniency;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
 * real card number, email address, IBAN or phone number, so that ordinary numbers stay readable.
 */
@UtilityClass
class ContentMaskingRules {

  // The patterns that can match long runs start a run, through a lookbehind, and match
  // possessively, so that matching stays linear on long hostile text.

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

  // After the password's first part, the match backtracks to the last @ before any /, ?, # or
  // space, or any quote, <, >, comma, semicolon, & or = that ends a URL written in other text. So
  // an
  // @ inside the password is masked, while an @ in the path, the query or the next value is never
  // reached, and a key such as password= is left for the key-value rule. Each scan stops at the
  // next URL's //, so matching stays linear.
  /**
   * A password in a URL, with or without a user name, such as {@code https://user:secret@host},
   * {@code redis://:secret@host} or {@code https://user:p@ss@host}.
   */
  private static final Pattern URL_CREDENTIALS =
      Pattern.compile(
          "(?i)(?<![a-z0-9+.-])([a-z][a-z0-9+.-]*+://)[^\\s/:@]*+:[^\\s/@]++"
              + "(?:@[^\\s/?#\"'<>,;&=]*@|@)");

  /** A US social security number. */
  private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

  /** A possible email address. */
  private static final Pattern EMAIL =
      Pattern.compile("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]++@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

  /** A possible IBAN, with optional spaces between groups. */
  private static final Pattern IBAN =
      Pattern.compile("(?i)\\b[a-z]{2}\\d{2}(?:\\s?[a-z0-9]){10,30}\\b");

  /** Google's phone number library, which knows every country's numbering plan. */
  private static final PhoneNumberUtil PHONE_NUMBER_PARSER = PhoneNumberUtil.getInstance();

  /**
   * Returns all the rules as one: secrets first, then personal data, then the service's own.
   *
   * @param fieldNames the names whose values a key-value pair masks
   * @param extraPatterns the service's own patterns, masked in full
   * @return a function that applies every rule in turn
   */
  static UnaryOperator<String> allRulesInOrder(
      SensitiveFieldNames fieldNames, List<Pattern> extraPatterns) {
    var keyValuePairMasker = new KeyValuePairMasker(fieldNames);
    var builtInRules =
        Stream.of(
            guardedRule(
                text -> text.contains("eyJ"), maskingMatches(JWT, match -> MaskedText.MASK)),
            guardedRule(
                text -> text.contains(" "),
                maskingMatches(AUTH_SCHEME, ContentMaskingRules::maskAuthScheme)),
            guardedRule(
                text -> text.contains("://"),
                maskingMatches(URL_CREDENTIALS, ContentMaskingRules::maskUrlCredentials)),
            guardedRule(ContentMaskingRules::mayHoldKeyValuePair, keyValuePairMasker::mask),
            // Twice, because text next to a value can hide it from the first search, such as a
            // card number right before an email address or a phone number, and masking that text
            // shows it to the second.
            ContentMaskingRules::maskPersonalData,
            ContentMaskingRules::maskPersonalData);
    var extraRules =
        extraPatterns.stream().map(pattern -> maskingMatches(pattern, match -> MaskedText.MASK));
    return Stream.concat(builtInRules, extraRules)
        .reduce(UnaryOperator.identity(), ContentMaskingRules::earlierThenLater);
  }

  /**
   * Masks card numbers, social security numbers, email addresses, IBANs and phone numbers. All are
   * looked for in the same text, and parts that overlap are masked as one. Masking one in turn
   * could break the shape of another that it overlaps, such as a card number inside an email
   * address, and leave the rest of that one visible.
   */
  private static String maskPersonalData(String logText) {
    var found =
        Stream.of(
                guardedFinder(ContentMaskingRules::hasDigit, CardNumberMasker::cardsIn),
                guardedFinder(text -> text.contains("-"), keepingLastFourOf(SSN, ssn -> true)),
                guardedFinder(
                    text -> text.contains("@"),
                    keepingLastFourOf(EMAIL, EmailValidator.getInstance()::isValid)),
                guardedFinder(ContentMaskingRules::hasDigit, ContentMaskingRules::ibansIn),
                guardedFinder(text -> text.contains("+"), ContentMaskingRules::phoneNumbersIn))
            .flatMap(finder -> finder.apply(logText).stream())
            .toList();
    return MaskedText.replace(logText, MaskedText.joiningOverlaps(found));
  }

  /** Returns a finder that searches only text that passes a cheap check, finding nothing else. */
  private static Function<String, List<Replacement>> guardedFinder(
      Predicate<String> mayMatch, Function<String, List<Replacement>> find) {
    return text -> mayMatch.test(text) ? find.apply(text) : List.of();
  }

  /** Returns a finder of the pattern's matches that are real, each keeping its last four. */
  private static Function<String, List<Replacement>> keepingLastFourOf(
      Pattern pattern, Predicate<String> isReal) {
    return text ->
        pattern
            .matcher(text)
            .results()
            .filter(match -> isReal.test(match.group()))
            .map(
                match ->
                    Replacement.builder()
                        .start(match.start())
                        .end(match.end())
                        .text(MaskedText.keepingLastFour(match.group()))
                        .build())
            .toList();
  }

  /** Returns a rule that masks only text that passes a cheap check, skipping the rest. */
  private static UnaryOperator<String> guardedRule(
      Predicate<String> mayMatch, UnaryOperator<String> mask) {
    return text -> mayMatch.test(text) ? mask.apply(text) : text;
  }

  /**
   * Returns a rule that masks every match of the pattern. The masked form is written as it is,
   * never read as a group reference.
   */
  private static UnaryOperator<String> maskingMatches(
      Pattern pattern, Function<MatchResult, String> maskForMatch) {
    return text ->
        pattern.matcher(text).replaceAll(maskForMatch.andThen(Matcher::quoteReplacement));
  }

  /** Returns one rule that applies the earlier rule and then the later one. */
  private static UnaryOperator<String> earlierThenLater(
      UnaryOperator<String> earlierRule, UnaryOperator<String> laterRule) {
    return text -> laterRule.apply(earlierRule.apply(text));
  }

  /** Masks the credentials after an authorization scheme, keeping the scheme. */
  private static String maskAuthScheme(MatchResult match) {
    return match.group(1) + " " + MaskedText.MASK;
  }

  /** Masks the user name and password of a URL, keeping its scheme. */
  private static String maskUrlCredentials(MatchResult match) {
    return match.group(1) + MaskedText.MASK + "@";
  }

  /**
   * Returns every valid IBAN, each keeping its last four characters. The pattern can take in words
   * after an IBAN, including the start of the next one, so the search goes on after the IBAN
   * itself.
   */
  private static List<Replacement> ibansIn(String text) {
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
                        candidate.group(),
                        words -> !words.isEmpty(),
                        ContentMaskingRules::withoutLastWord)
                    .filter(
                        words ->
                            ibanWithoutSpaces(words).length() == countryIbanFormat.getIbanLength())
                    .filter(words -> ibanValidator.isValid(ibanWithoutSpaces(words)))
                    .findFirst())
        .map(
            iban ->
                Replacement.builder()
                    .start(candidate.start())
                    .end(candidate.start() + iban.length())
                    .text(MaskedText.keepingLastFour(ibanWithoutSpaces(iban)))
                    .build());
  }

  /** Returns the words without the last one, or an empty string if there is only one. */
  private static String withoutLastWord(String words) {
    return words.substring(0, Math.max(words.lastIndexOf(' '), 0));
  }

  /** Returns an IBAN without spaces, in upper case. */
  private static String ibanWithoutSpaces(String iban) {
    return iban.replace(" ", "").toUpperCase(Locale.ROOT);
  }

  /**
   * Returns every phone number with a country code, such as {@code +44 20 7946 0958}, each keeping
   * its last four digits.
   */
  private static List<Replacement> phoneNumbersIn(String text) {
    var phoneNumbers = PHONE_NUMBER_PARSER.findNumbers(text, null, Leniency.VALID, Long.MAX_VALUE);
    return StreamSupport.stream(phoneNumbers.spliterator(), false)
        .map(
            phoneNumber ->
                Replacement.builder()
                    .start(phoneNumber.start())
                    .end(phoneNumber.end())
                    .text(MaskedText.keepingLastFour(phoneNumber.rawString()))
                    .build())
        .toList();
  }

  /** Returns whether the text holds {@code =}, {@code :} or {@code %3}, which every pair needs. */
  private static boolean mayHoldKeyValuePair(String text) {
    return text.indexOf('=') >= 0 || text.indexOf(':') >= 0 || text.contains("%3");
  }

  /** Returns whether the text holds a digit. */
  private static boolean hasDigit(String text) {
    return text.chars().anyMatch(Character::isDigit);
  }
}
