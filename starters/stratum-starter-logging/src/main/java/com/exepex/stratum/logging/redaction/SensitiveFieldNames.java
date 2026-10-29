package com.exepex.stratum.logging.redaction;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Masks a value by the name of its field or key, such as {@code password} or {@code email},
 * whatever the value looks like. Names are compared without case, {@code _} or {@code -}, and only
 * their last part counts, so {@code spring.datasource.password}, {@code X-Api-Key} and {@code
 * first_name} all match.
 *
 * <p>Most names are masked in full. Payment cards, national IDs and personal identifiers such as
 * {@code email} keep their last four characters.
 */
final class SensitiveFieldNames {

  /** Short credential names, masked in full only when the whole name matches. */
  private static final Set<String> SHORT_SECRET_NAMES =
      Set.of("pwd", "pass", "auth", "pin", "otp", "cvv", "cvc");

  /** Payment cards and national IDs, always masked; the last four characters stay visible. */
  private static final Set<String> SECRET_IDENTIFIER_NAMES =
      Set.of(
          "card",
          "cardnumber",
          "creditcard",
          "pan",
          "ssn",
          "nationalid",
          "taxid",
          "passport",
          "passportnumber");

  /** Personal data with no telltale shape, masked in full. */
  private static final Set<String> PERSONAL_DATA_NAMES =
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
  private static final Set<String> PERSONAL_IDENTIFIER_NAMES =
      Set.of("email", "phone", "mobile", "telephone", "fax", "cell", "msisdn", "iban");

  /**
   * Parts of a name that mark it as a secret wherever they appear, as in {@code dbPassword}, {@code
   * apiToken} or {@code aws_secret_access_key}. Short names such as {@code pin} only match whole.
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

  /** Headers whose value is a list, such as {@code Cookie: a=1; b=2}, masked as a whole. */
  private static final Set<String> LIST_HEADER_NAMES =
      Set.of("cookie", "setcookie", "authorization", "proxyauthorization");

  /** Names whose values are masked in full. */
  private final Set<String> maskedInFull;

  /** Names whose values keep their last four characters. */
  private final Set<String> maskedKeepingLastFour;

  /**
   * Takes Stratum's names plus the service's own, which are masked in full.
   *
   * @param extraKeys the service's names from {@code stratum.logging.redaction.extra-keys}
   */
  SensitiveFieldNames(Collection<String> extraKeys) {
    maskedInFull =
        normalized(
            Stream.of(SHORT_SECRET_NAMES, PERSONAL_DATA_NAMES, extraKeys)
                .flatMap(Collection::stream));
    maskedKeepingLastFour =
        normalized(
            Stream.of(SECRET_IDENTIFIER_NAMES, PERSONAL_IDENTIFIER_NAMES).flatMap(Set::stream));
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
    var key = normalize(name);
    // A name listed to be masked in full, such as an extra key, wins over keeping the last four.
    if (maskedInFull.contains(key)) {
      return Optional.of(MaskedText.MASK);
    }
    if (maskedKeepingLastFour.contains(key)) {
      return Optional.of(MaskedText.keepingLastFour(valueSupplier.get()));
    }
    return isSecret(key) ? Optional.of(MaskedText.MASK) : Optional.empty();
  }

  /**
   * Returns whether values under the name are masked.
   *
   * @param name the field or key name
   * @return whether the name is sensitive
   */
  boolean isSensitive(String name) {
    var key = normalize(name);
    return maskedKeepingLastFour.contains(key) || isSecret(key);
  }

  /** Returns whether a normalized name is masked in full. */
  private boolean isSecret(String key) {
    return maskedInFull.contains(key) || SECRET_NAME_PARTS.stream().anyMatch(key::contains);
  }

  /**
   * Returns whether the name is a header whose whole value, to the end of the line, is masked.
   *
   * @param name the key name
   * @return whether its value is a list masked as a whole
   */
  boolean isHeaderList(String name) {
    return LIST_HEADER_NAMES.contains(normalize(name));
  }

  /** Normalizes every name in the stream. */
  private Set<String> normalized(Stream<String> names) {
    return names.map(this::normalize).collect(Collectors.toUnmodifiableSet());
  }

  /** Keeps the part after the last dot, in lower case, without {@code _} or {@code -}. */
  private String normalize(String name) {
    return name.substring(name.lastIndexOf('.') + 1)
        .toLowerCase(Locale.ROOT)
        .replace("_", "")
        .replace("-", "");
  }
}
