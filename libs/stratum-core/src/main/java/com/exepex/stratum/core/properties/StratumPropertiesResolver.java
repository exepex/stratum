package com.exepex.stratum.core.properties;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Works out the settings a Stratum module uses: the values bound from properties, changed by the
 * service's {@link StratumPropertiesCustomizer} beans. Java configuration wins because it is
 * applied last. The result is validated again, so a bad value set in Java stops the application at
 * startup just as a bad value in a properties file does.
 *
 * <p>A module's auto-configuration calls {@link #resolve} once, at startup, and builds its beans
 * from the result.
 */
public final class StratumPropertiesResolver {

  /** Checks the Bean Validation constraints declared on the module's settings type. */
  private final Validator validator;

  /**
   * Creates a resolver that validates settings with the given validator.
   *
   * @param validator the validator that checks the constraints on each module's settings type,
   *     usually the application's {@code Validator} bean
   */
  public StratumPropertiesResolver(Validator validator) {
    this.validator = validator;
  }

  /**
   * Returns the settings a module uses: {@code bound}, changed by each customizer in turn.
   *
   * @param bound the settings bound from {@code stratum.<module>.*} properties
   * @param customizers the service's customizers for these settings, in the order to apply them; an
   *     auto-configuration passes {@code ObjectProvider.orderedStream()}
   * @param <P> the module's settings type
   * @return the settings after every customizer has run
   * @throws IllegalStateException if a customizer returns {@code null}
   * @throws ConstraintViolationException if a customizer set a value that breaks a constraint
   */
  public <P> P resolve(P bound, Stream<? extends StratumPropertiesCustomizer<P>> customizers) {
    var remaining = customizers.iterator();
    var effective = bound;
    while (remaining.hasNext()) {
      effective = customize(effective, remaining.next());
    }
    validate(effective);
    return effective;
  }

  /**
   * Runs one customizer and rejects a {@code null} result, naming the customizer at fault.
   *
   * @param properties the settings so far
   * @param customizer the customizer to run
   * @param <P> the module's settings type
   * @return the settings the customizer returned
   */
  private static <P> P customize(P properties, StratumPropertiesCustomizer<P> customizer) {
    var customized = customizer.customize(properties);
    if (customized == null) {
      throw new IllegalStateException(
          customizer.getClass().getName()
              + " returned null. Return the settings it received to leave them unchanged.");
    }
    return customized;
  }

  /**
   * Stops startup when the settings break a constraint. The message names each property and
   * constraint but never the value, which may be a secret.
   *
   * @param properties the settings after every customizer has run
   * @param <P> the module's settings type
   */
  private <P> void validate(P properties) {
    Set<ConstraintViolation<P>> violations = validator.validate(properties);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException(describe(properties, violations), violations);
    }
  }

  /**
   * Describes the violations in one sorted line, so that the message is the same on every run.
   *
   * @param properties the settings that were validated
   * @param violations the constraints they break
   * @param <P> the module's settings type
   * @return a message such as {@code "Invalid ExampleProperties after Java configuration: timeout
   *     must not be null"}
   */
  private static <P> String describe(P properties, Set<ConstraintViolation<P>> violations) {
    return violations.stream()
        .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
        .sorted()
        .collect(
            Collectors.joining(
                "; ",
                "Invalid " + properties.getClass().getSimpleName() + " after Java configuration: ",
                ""));
  }
}
