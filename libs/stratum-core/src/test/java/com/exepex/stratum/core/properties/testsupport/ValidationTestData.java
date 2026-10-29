package com.exepex.stratum.core.properties.testsupport;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import lombok.experimental.UtilityClass;

/** The Bean Validation validator shared by the properties tests. */
@UtilityClass
public class ValidationTestData {

  // Built once for the whole test run; the factory is released when the test JVM exits.
  /** Hibernate Validator, as Spring Boot uses it. */
  public static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();
}
