package com.exepex.stratum.web;

import com.exepex.stratum.core.error.ErrorDetail;
import jakarta.validation.ConstraintViolation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
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

  /**
   * A map key in a field path, such as {@code [customer-4711]} in {@code
   * quantities[customer-4711]}: any index that is not a list position. The caller chose the key,
   * and it can be personal data.
   */
  private static final Pattern MAP_KEY = Pattern.compile("\\[(?!\\d+])[^]]*]");

  /** What a map key in a field path becomes. */
  private static final String ANY_KEY = "[*]";

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
    var field =
        error instanceof FieldError fieldError
            ? MAP_KEY.matcher(fieldError.getField()).replaceAll(ANY_KEY)
            : WHOLE_OBJECT;
    if (error instanceof FieldError fieldError && fieldError.isBindingFailure()) {
      return new ErrorDetail(field, WRONG_TYPE);
    }
    var violation =
        error.contains(ConstraintViolation.class) ? error.unwrap(ConstraintViolation.class) : null;
    var rejectedValue =
        error instanceof FieldError fieldError ? fieldError.getRejectedValue() : null;
    return new ErrorDetail(field, safeMessage(error, violation, rejectedValue));
  }

  /** Returns the message of a parameter's error, checked against the parameter's rejected value. */
  private static String safeMessage(
      MessageSourceResolvable error, @Nullable ConstraintViolation<?> violation) {
    return safeMessage(error, violation, violation == null ? null : violation.getInvalidValue());
  }

  /**
   * Returns the error's message, or fixed text when the message quotes the rejected value: through
   * a Bean Validation template that names it, or as text found in the message. The second also
   * catches a template read from {@code ValidationMessages.properties} and a message a Spring
   * {@code Validator} wrote with {@code rejectValue}.
   */
  private static String safeMessage(
      MessageSourceResolvable error,
      @Nullable ConstraintViolation<?> violation,
      @Nullable Object rejectedValue) {
    var message = Objects.requireNonNullElse(error.getDefaultMessage(), NOT_VALID);
    var templateQuotesValue =
        violation != null && violation.getMessageTemplate().contains(QUOTES_REJECTED_VALUE);
    var messageQuotesValue =
        rejectedValue instanceof CharSequence value && !value.isEmpty() && message.contains(value);
    return templateQuotesValue || messageQuotesValue ? NOT_VALID : message;
  }
}
