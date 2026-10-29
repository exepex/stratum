package com.exepex.stratum.core.properties;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.properties.servicecode.CodedProperties;
import com.exepex.stratum.core.properties.servicecode.RangeProperties;
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
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;

@DisplayName("StratumPropertiesResolver names broken settings by property key and rule")
class InvalidPropertiesMessageTest {

  /** Settings that keep every constraint; each test breaks only the settings it is about. */
  private static final ClientProperties VALID =
      ClientProperties.builder()
          .readTimeout(1)
          .name("orders")
          .tokens(Map.of())
          .hosts(List.of())
          .pool(new Pool(1))
          .build();

  /** Hibernate Validator, as Spring Boot uses it; JUnit closes it after the last test. */
  @AutoClose
  private static final ValidatorFactory VALIDATOR_FACTORY =
      Validation.buildDefaultValidatorFactory();

  /** Checks the settings' constraints. */
  private final Validator validator = VALIDATOR_FACTORY.getValidator();

  /** Builds the startup error from the violations. */
  private final StratumPropertiesResolver resolver = new StratumPropertiesResolver(validator);

  @Test
  @DisplayName("a property is named by the kebab-case key a service sets")
  void namesThePropertyKey() {
    var properties = VALID.toBuilder().readTimeout(null).build();

    assertThat(describe(properties)).isEqualTo("stratum.client.read-timeout breaks @NotNull");
  }

  @Test
  @DisplayName("a rule is named with the limits set on it, in name order, but not its message")
  void namesTheRuleWithItsLimits() {
    var properties = VALID.toBuilder().name("a-name-that-is-too-long").build();

    assertThat(describe(properties)).isEqualTo("stratum.client.name breaks @Size(max=10, min=2)");
  }

  @Test
  @DisplayName("a constraint message that quotes the value never reaches the error")
  void neverQuotesTheRejectedValue() {
    var properties = VALID.toBuilder().tokens(Map.of("orders", "hunter2")).build();

    assertThat(describe(properties))
        .isEqualTo("stratum.client.tokens[*] breaks @Pattern(regexp=\\d+)")
        .doesNotContain("hunter2");
  }

  @Test
  @DisplayName("a map key, which may be a credential or a person's identifier, never reaches it")
  void neverQuotesMapKeys() {
    var properties = VALID.toBuilder().tokens(Map.of("alice@example.com", "")).build();

    assertThat(describe(properties))
        .contains("stratum.client.tokens[*] breaks @Pattern")
        .doesNotContain("alice");
  }

  @Test
  @DisplayName("a list index is shown as [*]")
  void hidesListIndexes() {
    var properties = VALID.toBuilder().hosts(List.of("ok", "")).build();

    assertThat(describe(properties)).isEqualTo("stratum.client.hosts[*] breaks @NotBlank");
  }

  @Test
  @DisplayName("nested settings marked @Valid are checked and named by their full key")
  void checksNestedPropertiesMarkedValid() {
    var properties = VALID.toBuilder().pool(new Pool(0)).build();

    assertThat(describe(properties)).isEqualTo("stratum.client.pool.max-size breaks @Positive");
  }

  @Test
  @DisplayName("a service's own constraint without public access is named with its limits")
  void namesNonPublicConstraints() {
    assertThat(describe(new CodedProperties("toolong")))
        .isEqualTo("stratum.coded.code breaks @ShortCode(region=us)");
  }

  @Test
  @DisplayName("the broken settings are listed in key order, whatever order they arrive in")
  void sortsByKey() {
    var properties =
        VALID.toBuilder()
            .readTimeout(null)
            .name("a-name-that-is-too-long")
            .pool(new Pool(0))
            .build();
    var reverseKeyOrder =
        validator.validate(properties).stream()
            .sorted(Comparator.comparing(this::path).reversed())
            .collect(Collectors.toCollection(LinkedHashSet::new));

    assertThat(resolver.describeViolations(properties, reverseKeyOrder))
        .isEqualTo(
            "stratum.client.name breaks @Size(max=10, min=2);"
                + " stratum.client.pool.max-size breaks @Positive;"
                + " stratum.client.read-timeout breaks @NotNull");
  }

  @Test
  @DisplayName("a rule on the settings type itself is named by the prefix alone")
  void namesTypeLevelRulesByPrefix() {
    assertThat(describe(new RangeProperties(2, 1))).isEqualTo("stratum.range breaks @MinBelowMax");
  }

  /** Validates the settings and returns the message the resolver would throw. */
  private <P> String describe(P properties) {
    return resolver.describeViolations(properties, validator.validate(properties));
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
  private record ClientProperties(
      @NotNull Integer readTimeout,
      @Size(min = 2, max = 10) String name,
      Map<String, @Pattern(regexp = "\\d+", message = "${validatedValue} is not all digits") String>
          tokens,
      List<@NotBlank String> hosts,
      @Valid Pool pool) {}

  /**
   * The connection pool of {@link ClientProperties}.
   *
   * @param maxSize the most connections the pool opens
   */
  private record Pool(@Positive int maxSize) {}
}
