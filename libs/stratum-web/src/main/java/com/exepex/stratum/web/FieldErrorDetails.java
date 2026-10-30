package com.exepex.stratum.web;

import com.exepex.stratum.core.error.ErrorDetail;
import jakarta.validation.ConstraintViolation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;

/**
 * Turns a failed validation of a request into one {@link ErrorDetail} per field, with messages that
 * never quote the rejected value, which can be personal data.
 */
@UtilityClass
public class FieldErrorDetails {

  /** The message of a value that could not be converted, such as {@code abc} for a number. */
  static final String WRONG_TYPE = "has the wrong type";

  /** The message of a constraint whose own message would quote the rejected value. */
  static final String NOT_VALID = "is not valid";

  /** The marker of a Bean Validation message template that quotes the rejected value. */
  private static final String QUOTES_REJECTED_VALUE = "validatedValue";

  /** The field name of a problem with the whole object, such as a class-level constraint. */
  private static final String WHOLE_OBJECT = "";

  /**
   * Returns the field problems of a failed validation, or nothing if the error is not one.
   *
   * @param error the error a request failed with
   * @return the problems, one per field error, when the error is a failed validation of a request
   *     body, form or method parameter
   */
  public static Optional<List<ErrorDetail>> of(Throwable error) {
    return switch (error) {
      case BindingResult bindingResult -> Optional.of(detailsOf(bindingResult.getAllErrors()));
      case MethodValidationResult methodValidation ->
          Optional.of(
              methodValidation.getParameterValidationResults().stream()
                  .flatMap(FieldErrorDetails::detailsOf)
                  .toList());
      default -> Optional.empty();
    };
  }

  /** Returns one detail per error of a bound object. */
  private static List<ErrorDetail> detailsOf(List<ObjectError> errors) {
    return errors.stream().map(FieldErrorDetails::detailOf).toList();
  }

  /** Returns one detail per error of a validated method parameter, named after the parameter. */
  private static Stream<ErrorDetail> detailsOf(ParameterValidationResult parameter) {
    var parameterName =
        Objects.requireNonNullElse(parameter.getMethodParameter().getParameterName(), WHOLE_OBJECT);
    return parameter.getResolvableErrors().stream()
        .map(
            resolvable ->
                resolvable instanceof ObjectError objectError
                    ? detailOf(objectError)
                    : new ErrorDetail(
                        parameterName,
                        safeMessage(
                            resolvable, parameter.unwrap(resolvable, ConstraintViolation.class))));
  }

  /** Returns the detail of one error of a bound object. */
  private static ErrorDetail detailOf(ObjectError error) {
    var field = error instanceof FieldError fieldError ? fieldError.getField() : WHOLE_OBJECT;
    if (error instanceof FieldError fieldError && fieldError.isBindingFailure()) {
      return new ErrorDetail(field, WRONG_TYPE);
    }
    var violation =
        error.contains(ConstraintViolation.class) ? error.unwrap(ConstraintViolation.class) : null;
    return new ErrorDetail(field, safeMessage(error, violation));
  }

  /**
   * Returns the error's message, or fixed text when its Bean Validation template quotes the
   * rejected value.
   */
  private static String safeMessage(MessageSourceResolvable error, @Nullable Object source) {
    var quotesValue =
        source instanceof ConstraintViolation<?> violation
            && violation.getMessageTemplate().contains(QUOTES_REJECTED_VALUE);
    return quotesValue
        ? NOT_VALID
        : Objects.requireNonNullElse(error.getDefaultMessage(), NOT_VALID);
  }
}
