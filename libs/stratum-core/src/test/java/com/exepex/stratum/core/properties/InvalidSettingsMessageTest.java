package com.exepex.stratum.core.properties;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.properties.servicecode.CodedSettings;
import com.exepex.stratum.core.properties.servicecode.RangeSettings;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Builder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;

@DisplayName("StratumPropertiesResolver names broken settings by property key and rule")
class InvalidSettingsMessageTest {

  /** Settings that keep every constraint; each test breaks only the settings it is about. */
  private static final ClientSettings VALID =
      ClientSettings.builder()
          .readTimeout(1)
          .name("orders")
          .tokens(Map.of())
          .hosts(List.of())
          .pool(new Pool(1))
          .build();

  private static ValidatorFactory validatorFactory;

  private static Validator validator;

  /** Builds the startup error from the violations. */
  private static StratumPropertiesResolver resolver;

  /** Builds one validator for every test. */
  @BeforeAll
  static void createValidator() {
    validatorFactory = Validation.buildDefaultValidatorFactory();
    validator = validatorFactory.getValidator();
    resolver = new StratumPropertiesResolver(validator);
  }

  /** Releases the validator factory's resources. */
  @AfterAll
  static void closeValidatorFactory() {
    validatorFactory.close();
  }

  @Test
  @DisplayName("a property is named by the kebab-case key a service sets")
  void namesThePropertyKey() {
    var settings = VALID.toBuilder().readTimeout(null).build();

    assertThat(describe(settings)).isEqualTo("stratum.client.read-timeout breaks @NotNull");
  }

  @Test
  @DisplayName("a rule is named with the limits set on it, in name order, but not its message")
  void namesTheRuleWithItsLimits() {
    var settings = VALID.toBuilder().name("a-name-that-is-too-long").build();

    assertThat(describe(settings)).isEqualTo("stratum.client.name breaks @Size(max=10, min=2)");
  }

  @Test
  @DisplayName("a constraint message that quotes the value never reaches the error")
  void neverQuotesTheRejectedValue() {
    var settings = VALID.toBuilder().tokens(Map.of("orders", "hunter2")).build();

    assertThat(describe(settings))
        .isEqualTo("stratum.client.tokens[*] breaks @Pattern(regexp=\\d+)")
        .doesNotContain("hunter2");
  }

  @Test
  @DisplayName("a map key, which may be a credential or a person's identifier, never reaches it")
  void neverQuotesMapKeys() {
    var settings = VALID.toBuilder().tokens(Map.of("alice@example.com", "")).build();

    assertThat(describe(settings))
        .contains("stratum.client.tokens[*] breaks @Pattern")
        .doesNotContain("alice");
  }

  @Test
  @DisplayName("a list index is shown as [*]")
  void hidesListIndexes() {
    var settings = VALID.toBuilder().hosts(List.of("ok", "")).build();

    assertThat(describe(settings)).isEqualTo("stratum.client.hosts[*] breaks @NotBlank");
  }

  @Test
  @DisplayName("nested settings marked @Valid are checked and named by their full key")
  void checksNestedSettingsMarkedValid() {
    var settings = VALID.toBuilder().pool(new Pool(0)).build();

    assertThat(describe(settings)).isEqualTo("stratum.client.pool.max-size breaks @Positive");
  }

  @Test
  @DisplayName("a service's own constraint without public access is named with its limits")
  void namesNonPublicConstraints() {
    assertThat(describe(new CodedSettings("toolong")))
        .isEqualTo("stratum.coded.code breaks @ShortCode(region=us)");
  }

  @Test
  @DisplayName("the broken settings are listed in key order, whatever order they arrive in")
  void sortsByKey() {
    var settings =
        VALID.toBuilder()
            .readTimeout(null)
            .name("a-name-that-is-too-long")
            .pool(new Pool(0))
            .build();
    var reverseKeyOrder =
        validator.validate(settings).stream()
            .sorted(Comparator.comparing(this::path).reversed())
            .collect(Collectors.toCollection(LinkedHashSet::new));

    assertThat(resolver.describeViolations(settings, reverseKeyOrder))
        .isEqualTo(
            "stratum.client.name breaks @Size(max=10, min=2);"
                + " stratum.client.pool.max-size breaks @Positive;"
                + " stratum.client.read-timeout breaks @NotNull");
  }

  @Test
  @DisplayName("a rule on the settings type itself is named by the prefix alone")
  void namesTypeLevelRulesByPrefix() {
    assertThat(describe(new RangeSettings(2, 1))).isEqualTo("stratum.range breaks @MinBelowMax");
  }

  /** Validates the settings and returns the message the resolver would throw. */
  private <P> String describe(P settings) {
    return resolver.describeViolations(settings, validator.validate(settings));
  }

  /** Returns the path of a broken constraint as text. */
  private String path(ConstraintViolation<?> violation) {
    return violation.getPropertyPath().toString();
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
  @Builder(toBuilder = true)
  @ConfigurationProperties("stratum.client")
  private record ClientSettings(
      @NotNull Integer readTimeout,
      @Size(min = 2, max = 10) String name,
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
}
