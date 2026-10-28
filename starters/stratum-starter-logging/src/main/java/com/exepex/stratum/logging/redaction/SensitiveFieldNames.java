package com.exepex.stratum.logging.redaction;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Masks a value by the name of its field or key, such as {@code password} or {@code email},
 * whatever the value looks like. Names are compared without case, {@code _} or {@code -}, and only
 * their last part counts, so {@code spring.datasource.password}, {@code X-Api-Key} and {@code
 * first_name} all match.
 *
 * <p>Names come in two groups. Secrets, payment cards and national IDs are always masked. Other
 * personal data is masked too; only that group may later be unmasked for a short time.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class SensitiveFieldNames {

  /** Short credential names, masked in full only when the whole name matches. */
  private static final Set<String> SECRETS = Set.of("pwd", "auth", "pin", "otp", "cvv", "cvc");

  /** Payment cards and national IDs, always masked; the last four characters stay visible. */
  private static final Set<String> SECRET_IDENTIFIERS =
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
  private static final Set<String> PERSONAL =
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
  private static final Set<String> PERSONAL_IDENTIFIERS =
      Set.of("email", "phone", "mobile", "telephone", "fax", "cell", "msisdn", "iban");

  /**
   * Parts of a name that mark it as a secret wherever they appear, as in {@code dbPassword}, {@code
   * apiToken} or {@code aws_secret_access_key}. Short names such as {@code pin} only match whole.
   */
  private static final List<String> SECRET_PARTS =
      List.of(
          "password",
          "passwd",
          "passphrase",
          "secret",
          "token",
          "apikey",
          "credential",
          "privatekey",
          "sessionid",
          "cookie",
          "authorization");

  /** Headers whose value is a list, such as {@code Cookie: a=1; b=2}, masked as a whole. */
  private static final Set<String> HEADER_LISTS =
      Set.of("cookie", "setcookie", "authorization", "proxyauthorization");

  /** Names whose values are masked in full. */
  private final Set<String> maskedInFull;

  /** Names whose values keep their last four characters. */
  private final Set<String> maskedKeepingLastFour;

  /**
   * Returns Stratum's names plus the service's own, which are masked in full.
   *
   * @param extraKeys the service's names from {@code stratum.logging.redaction.extra-keys}
   * @return the names
   */
  static SensitiveFieldNames withExtraKeys(Collection<String> extraKeys) {
    return new SensitiveFieldNames(
        normalized(Stream.of(SECRETS, PERSONAL, extraKeys).flatMap(Collection::stream)),
        normalized(Stream.of(SECRET_IDENTIFIERS, PERSONAL_IDENTIFIERS).flatMap(Set::stream)));
  }

  /**
   * Returns the masked value when the name is sensitive.
   *
   * @param name the field or key name; {@code null} when the value has none
   * @param value supplies the value, read only when its last four characters are kept
   * @return the masked value, or empty when the name is not sensitive
   */
  Optional<String> mask(@Nullable String name, Supplier<String> value) {
    if (name == null) {
      return Optional.empty();
    }
    var key = normalize(name);
    if (maskedKeepingLastFour.contains(key)) {
      return Optional.of(Redactor.keepingLastFour(value.get()));
    }
    return isSecret(key) ? Optional.of(Redactor.MASK) : Optional.empty();
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
    return maskedInFull.contains(key) || SECRET_PARTS.stream().anyMatch(key::contains);
  }

  /**
   * Returns whether the name is a header whose whole value, to the end of the line, is masked.
   *
   * @param name the key name
   * @return whether its value is a list masked as a whole
   */
  static boolean isHeaderList(String name) {
    return HEADER_LISTS.contains(normalize(name));
  }

  /** Normalizes every name in the stream. */
  private static Set<String> normalized(Stream<String> names) {
    return names.map(SensitiveFieldNames::normalize).collect(Collectors.toUnmodifiableSet());
  }

  /** Keeps the part after the last dot, in lower case, without {@code _} or {@code -}. */
  private static String normalize(String name) {
    return name.substring(name.lastIndexOf('.') + 1)
        .toLowerCase(Locale.ROOT)
        .replace("_", "")
        .replace("-", "");
  }
}
