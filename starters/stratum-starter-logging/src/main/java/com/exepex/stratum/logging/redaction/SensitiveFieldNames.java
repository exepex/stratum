package com.exepex.stratum.logging.redaction;

import java.util.Collection;
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

  /** Credentials, always masked in full. */
  private static final Set<String> SECRETS =
      Set.of(
          "password",
          "passwd",
          "pwd",
          "secret",
          "clientsecret",
          "apikey",
          "xapikey",
          "xauthtoken",
          "xaccesstoken",
          "accesstoken",
          "refreshtoken",
          "idtoken",
          "token",
          "authorization",
          "proxyauthorization",
          "auth",
          "credential",
          "credentials",
          "privatekey",
          "cookie",
          "setcookie",
          "sessionid",
          "pin",
          "otp",
          "cvv",
          "cvc");

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
    if (maskedInFull.contains(key)) {
      return Optional.of(Masks.MASK);
    }
    return maskedKeepingLastFour.contains(key)
        ? Optional.of(Masks.keepingLastFour(value.get()))
        : Optional.empty();
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
