package com.exepex.stratum.core.properties;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.ErrorReport;
import com.exepex.stratum.core.error.StratumException;
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

  /** Checks the Bean Validation constraints declared on the module's settings type. */
  private final Validator validator;

  /**
   * Returns the settings a module uses: {@code bound}, changed by each customizer in turn.
   *
   * @param bound the settings bound from {@code stratum.<module>.*} properties
   * @param customizers the service's customizers for these settings, in the order to apply them; an
   *     auto-configuration passes {@code ObjectProvider.orderedStream()}
   * @param <P> the module's settings type
   * @return the settings after every customizer has run
   * @throws StratumException {@code SX-CORE-1002} if a customizer is broken
   * @throws ConstraintViolationException {@code SX-CORE-1003} if the resulting settings break a
   *     constraint
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
   */
  private static <P> P customize(P properties, StratumPropertiesCustomizer<P> customizer) {
    var customizerName = customizer.getClass().getName();
    var settingsType = properties.getClass().getSimpleName();
    P customized;
    try {
      customized = customizer.customize(properties);
    } catch (ClassCastException e) {
      // Java erases generic types, so the customizer may be for other settings.
      throw new StratumException(
          CoreErrorCode.CUSTOMIZER_BROKEN,
          customizerName
              + " failed with a ClassCastException while changing "
              + settingsType
              + ". If it is for other settings, "
              + declareWithType(settingsType)
              + " Otherwise see the cause.",
          e);
    }
    if (!properties.getClass().isInstance(customized)) {
      throw new StratumException(
          CoreErrorCode.CUSTOMIZER_BROKEN,
          customizerName
              + " returned null or settings of another type. Return the settings it received to"
              + " keep them, and "
              + declareWithType(settingsType));
    }
    return customized;
  }

  /**
   * Tells the developer how to declare a customizer so that Spring gives it to the right module.
   */
  private static String declareWithType(String settingsType) {
    return "declare it as StratumPropertiesCustomizer<"
        + settingsType
        + ">, for example as the return type of a @Bean method.";
  }

  /**
   * Stops startup with {@code SX-CORE-1003} when the settings break a constraint, naming each
   * property key and rule but never a value, which may be a secret.
   */
  private <P> void validate(P properties) {
    Set<ConstraintViolation<P>> violations = validator.validate(properties);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException(
          ErrorReport.of(
                  CoreErrorCode.INVALID_SETTINGS,
                  ConstraintViolationMessages.describe(properties, violations))
              .getCodeAndMessage(),
          violations);
    }
  }
}
