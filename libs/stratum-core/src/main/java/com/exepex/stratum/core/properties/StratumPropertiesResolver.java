package com.exepex.stratum.core.properties;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;

/**
 * Works out the settings a Stratum module uses: the values bound from properties, changed by the
 * service's {@link StratumPropertiesCustomizer} beans. Java configuration wins because it is
 * applied last. Only the result is validated, so Java can correct or supply a value that the files
 * leave invalid or unset, and a bad value from either place stops the application at startup.
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
   * @throws ConstraintViolationException if the resulting settings break a constraint
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
   * @throws IllegalStateException if the customizer returns {@code null} or settings of another
   *     type, or fails with a {@code ClassCastException}
   */
  private static <P> P customize(P properties, StratumPropertiesCustomizer<P> customizer) {
    P customized;
    try {
      customized = customizer.customize(properties);
    } catch (ClassCastException e) {
      throw castFailed(properties, customizer, e);
    }
    if (customized == null) {
      throw new IllegalStateException(
          customizer.getClass().getName()
              + " returned null. Return the settings it received to leave them unchanged.");
    }
    if (!properties.getClass().isInstance(customized)) {
      throw wrongType(properties, customizer);
    }
    return customized;
  }

  /**
   * Explains that a customizer returned settings of another type. Spring matches a customizer to a
   * module by its generic type, so this happens when the customizer is for another module, or was
   * registered without its type, for example with {@code
   * registerBean(StratumPropertiesCustomizer.class, ...)}.
   *
   * @param properties the settings the customizer was given
   * @param customizer the customizer at fault
   * @param <P> the module's settings type
   * @return the exception to throw
   */
  private static <P> IllegalStateException wrongType(
      P properties, StratumPropertiesCustomizer<P> customizer) {
    var settingsType = properties.getClass().getSimpleName();
    return new IllegalStateException(
        customizer.getClass().getName()
            + " cannot change "
            + settingsType
            + ": it is a customizer for other settings, or it was registered without its type."
            + " To fix it, "
            + declareWithType(settingsType));
  }

  /**
   * Explains a {@code ClassCastException} from inside a customizer. Java erases generic types, so
   * this happens both when a customizer for other settings is given these settings and when the
   * customizer's own code has a bad cast. The message covers both cases, and the cause shows which
   * one it was.
   *
   * @param properties the settings the customizer was given
   * @param customizer the customizer at fault
   * @param cause the failure inside the customizer
   * @param <P> the module's settings type
   * @return the exception to throw
   */
  private static <P> IllegalStateException castFailed(
      P properties, StratumPropertiesCustomizer<P> customizer, ClassCastException cause) {
    var settingsType = properties.getClass().getSimpleName();
    return new IllegalStateException(
        customizer.getClass().getName()
            + " failed with a ClassCastException while changing "
            + settingsType
            + ". If it is a customizer for other settings, or was registered without its type, "
            + declareWithType(settingsType)
            + " Otherwise the cast that failed is in the customizer's own code; see the cause.",
        cause);
  }

  /**
   * Tells the developer how to declare a customizer so that Spring gives it to the right module.
   *
   * @param settingsType the simple name of the module's settings type
   * @return the advice, as the end of a sentence
   */
  private static String declareWithType(String settingsType) {
    return "declare it as StratumPropertiesCustomizer<"
        + settingsType
        + ">, for example as the return type of a @Bean method.";
  }

  /**
   * Stops startup when the settings break a constraint. The message names each property key and
   * rule but never the value, which may be a secret ({@link ConstraintViolationMessages}).
   *
   * @param properties the settings after every customizer has run
   * @param <P> the module's settings type
   */
  private <P> void validate(P properties) {
    Set<ConstraintViolation<P>> violations = validator.validate(properties);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException(
          ConstraintViolationMessages.describe(properties, violations), violations);
    }
  }
}
