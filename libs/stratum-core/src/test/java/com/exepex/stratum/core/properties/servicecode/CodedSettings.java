package com.exepex.stratum.core.properties.servicecode;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings checked by a constraint that is not public.
 *
 * @param code a code of at most 3 characters
 */
@ConfigurationProperties("stratum.coded")
public record CodedSettings(@CodedSettings.ShortCode(region = "us") String code) {

  /** A code of at most 3 characters, declared without public access. */
  @Constraint(validatedBy = {})
  @Size(max = 3)
  @ReportAsSingleViolation
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
  @interface ShortCode {

    /**
     * The message when the code is too long.
     *
     * @return the message
     */
    String message() default "code too long";

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

    /**
     * The region the code belongs to.
     *
     * @return the region
     */
    String region() default "eu";
  }
}
