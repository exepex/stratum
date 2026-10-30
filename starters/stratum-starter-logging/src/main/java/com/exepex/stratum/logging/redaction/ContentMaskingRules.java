package com.exepex.stratum.logging.redaction;

import com.exepex.stratum.logging.redaction.MaskedText.Replacement;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.Leniency;
import java.util.List;
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

  // A URL ends at a space, /, ?, #, a quote, < or >. A comma, semicolon, & or = may be part of
  // the password or start the next value, as in https://u:p@host,password=x@y. Each scan is
  // possessive or backtracks once, and stops at the next URL's //, so matching stays linear.
  /**
   * A password in a URL whose end is unclear, because another {@code @} follows a comma, semicolon,
   * {@code &} or {@code =}, with the rest of the line. All of it is masked: masking less could show
   * the password's end, or the key of the next secret and so its value.
   */
  private static final Pattern URL_PASSWORD_OF_UNCLEAR_END =
      Pattern.compile(
          "(?i)(?<![a-z0-9+.-])([a-z][a-z0-9+.-]*+://)[^\\s/:@]*+:[^\\s/@]++@"
              + "(?=[^\\s/?#\"'<>,;&=]*+[,;&=][^\\s/?#\"'<>]*@)[^\\r\\n]*+");

  /**
   * A password in a URL, with or without a user name, such as {@code https://user:secret@host},
   * {@code redis://:secret@host} or {@code https://user:p@ss@host}. The match backtracks to the
   * last @ before the URL ends or a comma, semicolon, & or =, so an @ inside the password is
   * masked.
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
                maskingMatches(
                    URL_PASSWORD_OF_UNCLEAR_END, match -> match.group(1) + MaskedText.MASK)),
            guardedRule(
                text -> text.contains("://"),
                maskingMatches(URL_CREDENTIALS, ContentMaskingRules::maskUrlCredentials)),
            guardedRule(ContentMaskingRules::mayHoldKeyValuePair, keyValuePairMasker::mask),
            ContentMaskingRules::maskPersonalDataTwice);
    var extraRules =
        extraPatterns.stream().map(pattern -> maskingMatches(pattern, match -> MaskedText.MASK));
    return Stream.concat(builtInRules, extraRules)
        .reduce(UnaryOperator.identity(), ContentMaskingRules::earlierThenLater);
  }

  /**
   * Masks personal data twice, because text next to a value can hide it from the first search, such
   * as a card number right before an email address or a phone number, and masking that text shows
   * it to the second. Text that the first search left unchanged is not searched again.
   */
  private static String maskPersonalDataTwice(String logText) {
    var maskedOnce = maskPersonalData(logText);
    return maskedOnce.equals(logText) ? maskedOnce : maskPersonalData(maskedOnce);
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
                guardedFinder(ContentMaskingRules::hasDigit, IbanMasker::ibansIn),
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
            .map(match -> MaskedText.keepingLastFourAt(match.start(), match.end(), match.group()))
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
   * Returns every phone number with a country code, such as {@code +44 20 7946 0958}, each keeping
   * its last four digits.
   */
  private static List<Replacement> phoneNumbersIn(String text) {
    var phoneNumbers = PHONE_NUMBER_PARSER.findNumbers(text, null, Leniency.VALID, Long.MAX_VALUE);
    return StreamSupport.stream(phoneNumbers.spliterator(), false)
        .map(
            phoneNumber ->
                MaskedText.keepingLastFourAt(
                    phoneNumber.start(), phoneNumber.end(), phoneNumber.rawString()))
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
