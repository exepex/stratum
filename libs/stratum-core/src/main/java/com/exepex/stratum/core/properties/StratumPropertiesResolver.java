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
   * @throws StratumException if a customizer returns {@code null} or settings of another type
   * @throws ConstraintViolationException {@code SX-CORE-1005} if the resulting settings break a
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
   *
   * @param properties the settings so far
   * @param customizer the customizer to run
   * @param <P> the module's settings type
   * @return the settings the customizer returned
   * @throws StratumException {@code SX-CORE-1002} if the customizer returns {@code null}, {@code
   *     SX-CORE-1003} if it returns settings of another type, or {@code SX-CORE-1004} if it fails
   *     with a {@code ClassCastException}
   */
  private static <P> P customize(P properties, StratumPropertiesCustomizer<P> customizer) {
    var customizerName = customizer.getClass().getName();
    var settingsType = properties.getClass().getSimpleName();
    P customized;
    try {
      customized = customizer.customize(properties);
    } catch (ClassCastException e) {
      // Java erases generic types, so this is either a customizer for other settings or a bad
      // cast in the customizer's own code; the message covers both and the cause tells which.
      throw new StratumException(
          e, CoreErrorCode.CUSTOMIZER_CAST_FAILED, customizerName, settingsType);
    }
    if (customized == null) {
      throw new StratumException(CoreErrorCode.CUSTOMIZER_RETURNED_NULL, customizerName);
    }
    if (!properties.getClass().isInstance(customized)) {
      // Spring matches a customizer to a module by its generic type, so this is a customizer for
      // another module, or one registered without its type.
      throw new StratumException(
          CoreErrorCode.CUSTOMIZER_FOR_OTHER_SETTINGS, customizerName, settingsType);
    }
    return customized;
  }

  /**
   * Stops startup when the settings break a constraint. The message starts with {@code
   * SX-CORE-1005} and names each property key and rule but never the value, which may be a secret
   * ({@link ConstraintViolationMessages}). It stays a {@code ConstraintViolationException}, so that
   * the broken constraints remain available to tools that read them.
   *
   * @param properties the settings after every customizer has run
   * @param <P> the module's settings type
   */
  private <P> void validate(P properties) {
    Set<ConstraintViolation<P>> violations = validator.validate(properties);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException(
          ErrorReport.of(
                  CoreErrorCode.INVALID_SETTINGS,
                  properties.getClass().getSimpleName(),
                  ConstraintViolationMessages.describe(properties, violations))
              .getCodeAndMessage(),
          violations);
    }
  }
}
