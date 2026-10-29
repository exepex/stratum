package com.exepex.stratum.core.properties.servicecode;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings checked by a rule on the whole type rather than on one property.
 *
 * @param min the lowest value
 * @param max the highest value
 */
@RangeSettings.MinBelowMax
@ConfigurationProperties("stratum.range")
public record RangeSettings(int min, int max) {

  /** A rule on a whole {@link RangeSettings}: its minimum is below its maximum. */
  @Constraint(validatedBy = MinBelowMax.Check.class)
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  public @interface MinBelowMax {

    /**
     * The message when the minimum is not below the maximum.
     *
     * @return the message
     */
    String message() default "min must be below max";

    /**
     * The validation groups.
     *
     * @return the groups
     */
    Class<?>[] groups() default {};

    /**
     * The payload.
     *
     * @return the payload
     */
    Class<? extends Payload>[] payload() default {};

    /** Checks {@link MinBelowMax}; public, because the validator creates it. */
    final class Check implements ConstraintValidator<MinBelowMax, RangeSettings> {

      /**
       * Returns whether the minimum is below the maximum.
       *
       * @param range the settings to check
       * @param context the validation context
       * @return whether the settings keep the rule
       */
      @Override
      public boolean isValid(RangeSettings range, ConstraintValidatorContext context) {
        return range.min() < range.max();
      }
    }
  }
}
