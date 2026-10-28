package com.exepex.stratum.core.properties;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.properties.servicecode.CodedSettings;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;

@DisplayName("ConstraintViolationMessages names broken settings by property key and rule")
class ConstraintViolationMessagesTest {

  /** A client name that keeps every constraint, for tests about other settings. */
  private static final String NAME = "orders";

  /** A pool that keeps every constraint, for tests about other settings. */
  private static final Pool VALID_POOL = new Pool(1);

  private static ValidatorFactory validatorFactory;

  private static Validator validator;

  /** Builds one validator for every test. */
  @BeforeAll
  static void createValidator() {
    validatorFactory = Validation.buildDefaultValidatorFactory();
    validator = validatorFactory.getValidator();
  }

  /** Releases the validator factory's resources. */
  @AfterAll
  static void closeValidatorFactory() {
    validatorFactory.close();
  }

  @Test
  @DisplayName("a property is named by the kebab-case key a service sets")
  void namesThePropertyKey() {
    var settings = new ClientSettings(null, NAME, Map.of(), List.of(), VALID_POOL);

    assertThat(describe(settings))
        .isEqualTo("Invalid ClientSettings: stratum.client.read-timeout breaks @NotNull");
  }

  @Test
  @DisplayName("a rule is named with the limits set on it, but not its message")
  void namesTheRuleWithItsLimits() {
    var settings =
        new ClientSettings(1, "a-name-that-is-too-long", Map.of(), List.of(), VALID_POOL);

    assertThat(describe(settings))
        .isEqualTo("Invalid ClientSettings: stratum.client.name breaks @Size(max=10)");
  }

  @Test
  @DisplayName("a constraint message that quotes the value never reaches the error")
  void neverQuotesTheRejectedValue() {
    var settings = new ClientSettings(1, NAME, Map.of("orders", "hunter2"), List.of(), VALID_POOL);

    assertThat(describe(settings))
        .isEqualTo("Invalid ClientSettings: stratum.client.tokens[*] breaks @Pattern(regexp=\\d+)")
        .doesNotContain("hunter2");
  }

  @Test
  @DisplayName("a map key, which may be a credential or a person's identifier, never reaches it")
  void neverQuotesMapKeys() {
    var settings =
        new ClientSettings(1, NAME, Map.of("alice@example.com", ""), List.of(), VALID_POOL);

    assertThat(describe(settings))
        .contains("stratum.client.tokens[*] breaks @Pattern")
        .doesNotContain("alice");
  }

  @Test
  @DisplayName("a list index is shown as [*]")
  void hidesListIndexes() {
    var settings = new ClientSettings(1, NAME, Map.of(), List.of("ok", ""), VALID_POOL);

    assertThat(describe(settings))
        .isEqualTo("Invalid ClientSettings: stratum.client.hosts[*] breaks @NotBlank");
  }

  @Test
  @DisplayName("nested settings marked @Valid are checked and named by their full key")
  void checksNestedSettingsMarkedValid() {
    var settings = new ClientSettings(1, NAME, Map.of(), List.of(), new Pool(0));

    assertThat(describe(settings))
        .isEqualTo("Invalid ClientSettings: stratum.client.pool.max-size breaks @Positive");
  }

  @Test
  @DisplayName("settings without @ConfigurationProperties are named by their own keys")
  void namesKeysWithoutPrefix() {
    assertThat(describe(new Unprefixed("")))
        .isEqualTo("Invalid Unprefixed: value breaks @NotBlank");
  }

  @Test
  @DisplayName("a service's own constraint without public access is named with its limits")
  void namesNonPublicConstraints() {
    assertThat(describe(new CodedSettings("toolong")))
        .isEqualTo(
            "Invalid CodedSettings: stratum.coded.code"
                + " breaks @ShortCode(format=String, region=us)");
  }

  /**
   * Validates the settings and returns the message the resolver would throw.
   *
   * @param settings the settings to validate
   * @param <P> the settings type
   * @return the message
   */
  private static <P> String describe(P settings) {
    return ConstraintViolationMessages.describe(settings, validator.validate(settings));
  }

  /**
   * Settings of an example HTTP client, with one constraint of each kind the messages handle.
   *
   * @param readTimeout seconds to wait for a response
   * @param name the client's name, at most 10 characters
   * @param tokens a token per user, each all digits; the message quotes the value on purpose
   * @param hosts the hosts to call, none blank
   * @param pool the connection pool
   */
  @ConfigurationProperties("stratum.client")
  private record ClientSettings(
      @NotNull Integer readTimeout,
      @Size(max = 10) String name,
      Map<String, @Pattern(regexp = "\\d+", message = "${validatedValue} is not all digits") String>
          tokens,
      List<@NotBlank String> hosts,
      @Valid Pool pool) {}

  /**
   * The connection pool of {@link ClientSettings}.
   *
   * @param maxSize the most connections the pool opens
   */
  private record Pool(@Positive int maxSize) {}

  /**
   * Settings that are not bound from properties.
   *
   * @param value a value that must not be blank
   */
  private record Unprefixed(@NotBlank String value) {}
}
