package com.exepex.stratum.logging.redaction;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Masks a value by the name of its field or key, such as {@code password} or {@code email},
 * whatever the value looks like. A name is split into words ({@link FieldNameWords}), and it is
 * sensitive when one word, or a few words written together, is a sensitive word: {@code dbPwd},
 * {@code card_cvv}, {@code X-API-Key} and {@code spring.datasource.password} all match, while
 * {@code shipping} and {@code passenger} do not. A name whose last word only describes the value,
 * such as {@code tokenCount} or {@code passwordExpiry}, is not sensitive.
 *
 * <p>Most names are masked in full. Payment cards, national IDs and personal identifiers such as
 * {@code email} keep their last four characters, unless the name also holds a word masked in full.
 * The service's own names are matched whole, without case, {@code _} or {@code -}, on their last
 * dot-separated part.
 */
final class SensitiveFieldNames {

  /** Credential words, masked in full. */
  private static final Set<String> SECRET_WORDS =
      Set.of(
          "pwd",
          "pass",
          "auth",
          "pin",
          "otp",
          "cvv",
          "cvc",
          "jwt",
          "accesskey",
          "encryptionkey",
          "signingkey");

  /** Payment cards, accounts and national IDs; the last four characters stay visible. */
  private static final Set<String> SECRET_IDENTIFIER_WORDS =
      Set.of(
          "card",
          "cardnumber",
          "creditcard",
          "pan",
          "accountnumber",
          "ssn",
          "nationalid",
          "taxid",
          "passport",
          "passportnumber");

  /** Personal data with no telltale shape, masked in full. */
  private static final Set<String> PERSONAL_DATA_WORDS =
      Set.of(
          "firstname",
          "lastname",
          "middlename",
          "fullname",
          "givenname",
          "surname",
          "username",
          "displayname",
          "dob",
          "dateofbirth",
          "birthdate",
          "address",
          "streetaddress");

  /** Personal identifiers, masked with the last four characters visible. */
  private static final Set<String> PERSONAL_IDENTIFIER_WORDS =
      Set.of("email", "phone", "mobile", "telephone", "fax", "cell", "msisdn", "iban");

  /**
   * Long credential words that mark a name as a secret wherever they appear, even inside a word, as
   * in {@code dbpassword} or {@code JSESSIONID}.
   */
  private static final List<String> SECRET_NAME_PARTS =
      List.of(
          "password",
          "passwd",
          "passphrase",
          "passcode",
          "secret",
          "token",
          "apikey",
          "credential",
          "privatekey",
          "sessionid",
          "cookie",
          "authorization");

  /**
   * Last words that say something about a secret rather than hold it, such as {@code tokenCount} or
   * {@code pinRequired}; a name ending in one is not masked.
   */
  private static final Set<String> ABOUT_WORDS =
      Set.of(
          "count",
          "type",
          "length",
          "size",
          "expiry",
          "expires",
          "enabled",
          "required",
          "consent",
          "rotated");

  /** Endings of headers whose value is a list, such as {@code Cookie: a=1; b=2}. */
  private static final List<String> LIST_HEADER_ENDINGS = List.of("cookie", "authorization");

  /** The most words a sensitive word is written in, as {@code date_of_birth} is. */
  private static final int LONGEST_RUN = 3;

  /** Words and runs of words masked in full. */
  private static final Set<String> MASKED_IN_FULL =
      Stream.of(SECRET_WORDS, PERSONAL_DATA_WORDS)
          .flatMap(Set::stream)
          .collect(Collectors.toUnmodifiableSet());

  /** Words and runs of words that keep their last four characters. */
  private static final Set<String> MASKED_KEEPING_LAST_FOUR =
      Stream.of(SECRET_IDENTIFIER_WORDS, PERSONAL_IDENTIFIER_WORDS)
          .flatMap(Set::stream)
          .collect(Collectors.toUnmodifiableSet());

  /** How a name's values are masked. */
  private enum Masking {
    /** Replaced by the mask. */
    IN_FULL,
    /** Replaced by the mask, keeping the last four characters. */
    KEEPING_LAST_FOUR,
    /** Not masked by name. */
    NONE
  }

  /** The service's own names, normalized, masked in full. */
  private final Set<String> extraKeys;

  /**
   * Takes the service's own names, which are masked in full.
   *
   * @param extraKeys the service's names from {@code stratum.logging.redaction.extra-keys}
   */
  SensitiveFieldNames(Collection<String> extraKeys) {
    this.extraKeys =
        extraKeys.stream().map(this::normalize).collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Returns the masked value when the name is sensitive.
   *
   * @param name the field or key name; {@code null} when the value has none
   * @param valueSupplier supplies the value, read only when its last four characters are kept
   * @return the masked value, or empty when the name is not sensitive
   */
  Optional<String> mask(@Nullable String name, Supplier<String> valueSupplier) {
    if (name == null) {
      return Optional.empty();
    }
    return switch (maskingOf(name)) {
      case IN_FULL -> Optional.of(MaskedText.MASK);
      case KEEPING_LAST_FOUR -> Optional.of(MaskedText.keepingLastFour(valueSupplier.get()));
      case NONE -> Optional.empty();
    };
  }

  /**
   * Returns whether the name is one of the service's own extra keys.
   *
   * @param name the field or key name; {@code null} when the value has none
   * @return whether the service listed the name
   */
  boolean isExtraKey(@Nullable String name) {
    return name != null && extraKeys.contains(normalize(name));
  }

  /**
   * Returns whether values under the name are masked.
   *
   * @param name the field or key name
   * @return whether the name is sensitive
   */
  boolean isSensitive(String name) {
    return maskingOf(name) != Masking.NONE;
  }

  /**
   * Returns whether the name is a header whose whole value, to the end of the line, is masked.
   *
   * @param name the key name
   * @return whether its value is a list masked as a whole
   */
  boolean isHeaderList(String name) {
    // Header names are case-insensitive, so sEt-cOoKiE is matched on its letters, not its words.
    var key = normalize(name);
    return LIST_HEADER_ENDINGS.stream().anyMatch(key::endsWith);
  }

  /** Returns how values under the name are masked; a service's own name is masked in full. */
  private Masking maskingOf(String name) {
    return isExtraKey(name) ? Masking.IN_FULL : maskingOfWords(FieldNameWords.wordsOf(name));
  }

  /** Returns how values are masked under a name with these words; masking in full wins. */
  private Masking maskingOfWords(List<String> words) {
    if (words.isEmpty() || ABOUT_WORDS.contains(words.getLast())) {
      return Masking.NONE;
    }
    var joined = String.join("", words);
    // The whole name written together also counts, so a name in odd case such as pWd still matches.
    var runs = Stream.concat(runsOf(words).stream(), Stream.of(joined)).toList();
    if (runs.stream().anyMatch(MASKED_IN_FULL::contains)
        || SECRET_NAME_PARTS.stream().anyMatch(joined::contains)) {
      return Masking.IN_FULL;
    }
    return runs.stream().anyMatch(MASKED_KEEPING_LAST_FOUR::contains)
        ? Masking.KEEPING_LAST_FOUR
        : Masking.NONE;
  }

  /** Returns every word and every run of up to three consecutive words, written together. */
  private List<String> runsOf(List<String> words) {
    return IntStream.range(0, words.size())
        .boxed()
        .flatMap(
            start ->
                IntStream.rangeClosed(start + 1, Math.min(words.size(), start + LONGEST_RUN))
                    .mapToObj(end -> String.join("", words.subList(start, end))))
        .toList();
  }

  /** Keeps the part after the last dot, in lower case, without {@code _} or {@code -}. */
  private String normalize(String name) {
    return name.substring(name.lastIndexOf('.') + 1)
        .toLowerCase(Locale.ROOT)
        .replace("_", "")
        .replace("-", "");
  }
}
