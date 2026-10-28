package com.exepex.stratum.core.properties;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Works out the settings a Stratum module uses: the values bound from properties, changed by the
 * service's {@link StratumPropertiesCustomizer} beans. Java configuration wins because it is
 * applied last. The result is validated again, so a bad value set in Java stops the application at
 * startup just as a bad value in a properties file does.
 *
 * <p>A module's auto-configuration calls {@link #resolve} once, at startup, and builds its beans
 * from the result.
 */
@RequiredArgsConstructor
public final class StratumPropertiesResolver {

  /**
   * Checks the Bean Validation constraints declared on the module's settings type. A module passes
   * the application's {@code Validator} bean, which Spring Boot creates because {@code
   * stratum-core} brings Hibernate Validator.
   */
  private final Validator validator;

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
   * Runs one customizer and rejects a result the module cannot use, naming the customizer at fault.
   *
   * @param properties the settings so far
   * @param customizer the customizer to run
   * @param <P> the module's settings type
   * @return the settings the customizer returned
   * @throws IllegalStateException if the customizer returns {@code null}, or is meant for another
   *     settings type
   */
  private static <P> P customize(P properties, StratumPropertiesCustomizer<P> customizer) {
    P customized;
    try {
      customized = customizer.customize(properties);
    } catch (ClassCastException e) {
      throw wrongType(properties, customizer, e);
    }
    if (customized == null) {
      throw new IllegalStateException(
          customizer.getClass().getName()
              + " returned null. Return the settings it received to leave them unchanged.");
    }
    if (!properties.getClass().isInstance(customized)) {
      throw wrongType(properties, customizer, null);
    }
    return customized;
  }

  /**
   * Explains that a customizer reached settings it was not written for. Spring matches a customizer
   * to a module by its generic type, so this happens when the customizer is for another module, or
   * was registered without its type, for example with {@code
   * registerBean(StratumPropertiesCustomizer.class, ...)}.
   *
   * @param properties the settings the customizer was given
   * @param customizer the customizer at fault
   * @param cause the failure inside the customizer, or {@code null} if it returned the wrong type
   * @param <P> the module's settings type
   * @return the exception to throw
   */
  private static <P> IllegalStateException wrongType(
      P properties, StratumPropertiesCustomizer<P> customizer, @Nullable ClassCastException cause) {
    var settingsType = properties.getClass().getSimpleName();
    return new IllegalStateException(
        customizer.getClass().getName()
            + " cannot change "
            + settingsType
            + ": it is a customizer for other settings, or it was registered without its type."
            + " Declare it as StratumPropertiesCustomizer<"
            + settingsType
            + ">, for example as the return type of a @Bean method.",
        cause);
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
