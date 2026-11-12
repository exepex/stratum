package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.exepex.stratum.cache.CacheJsonValues.AllowedPackagesValidator;
import com.exepex.stratum.cache.testsupport.values.CachedValuesTestData;
import com.exepex.stratum.cache.testsupport.values.Payment;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import tools.jackson.databind.DatabindContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator.Validity;

@DisplayName("The JSON values check a class named inside the JSON by its name, before it is loaded")
class AllowedPackagesValidatorTest {

  /** The names, each with the answer the name check gives before Jackson loads the class. */
  private static final List<Arguments> NAMES =
      List.of(
          Arguments.of(CachedValuesTestData.GADGET_CLASS, Validity.DENIED),
          Arguments.of("java.util.Timer", Validity.DENIED),
          Arguments.of(Payment.class.getName(), Validity.INDETERMINATE));

  /** The validator of the JSON values that allow only the test values' package. */
  private final AllowedPackagesValidator validator =
      new AllowedPackagesValidator(List.of(CachedValuesTestData.ALLOWED_PACKAGE + "."));

  /** The declared base type, a payment. */
  private final JavaType baseType =
      CachedValuesTestData.jsonValues().typeFactory().constructType(Payment.class);

  @ParameterizedTest(name = "{0}: {1}")
  @FieldSource("NAMES")
  @DisplayName(
      "a name outside the allowed packages, java included, is denied; an allowed one goes on")
  void checksNameBeforeLoading(String className, Validity expected) {
    assertThat(validator.validateSubClassName(mock(DatabindContext.class), baseType, className))
        .isEqualTo(expected);
  }
}
