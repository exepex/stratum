package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.ErrorDetail;
import jakarta.validation.Validation;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.validation.BindException;
import org.springframework.validation.DataBinder;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

@DisplayName("FieldErrorDetails lists each invalid field without quoting its value")
class FieldErrorDetailsTest {

  /** An IBAN, which a message that quotes the rejected value would leak. */
  private static final String IBAN = "DE89370400440532013000";

  /** The form's field that holds how many items are ordered. */
  private static final String QUANTITY = "quantity";

  /** The form's field that holds the account to pay from. */
  private static final String ACCOUNT = "account";

  /** An order form as a service validates it. */
  @Getter
  @Setter
  static class OrderForm {

    /** How many items; at least one. */
    @Min(1)
    private int quantity = 1;

    /** The account to pay from; its message quotes the rejected value, as some services do. */
    @Pattern(regexp = "[A-Z]{2}", message = "${validatedValue} is not a country")
    private String account = "DE";

    /** A second account; its message comes from ValidationMessages.properties and quotes it. */
    @Pattern(regexp = "[A-Z]{2}", message = "{orders.backup-account.invalid}")
    private String backupAccount = "DE";

    /** How many items each customer orders, keyed by the customer's reference. */
    private Map<String, @Min(1) Integer> quantitiesByCustomer = new ConcurrentHashMap<>();

    /**
     * A rule on the whole form: an account of {@code XX} is never allowed.
     *
     * @return whether the account is allowed
     */
    @AssertTrue(message = "is not allowed")
    public boolean isAllowedAccount() {
      return !"XX".equals(account);
    }
  }

  /** Binds and validates the form from request values, as Spring MVC does. */
  private BindException failedBinding(MutablePropertyValues requestValues) {
    var binder = new DataBinder(new OrderForm(), "orderForm");
    try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
      binder.setValidator(new SpringValidatorAdapter(validatorFactory.getValidator()));
      binder.bind(requestValues);
      binder.validate();
    }
    return new BindException(binder.getBindingResult());
  }

  @Test
  @DisplayName("a value of the wrong type gets fixed text, not the conversion error")
  void wrongTypeGetsFixedText() {
    var failure = failedBinding(new MutablePropertyValues().add(QUANTITY, "abc"));

    assertThat(FieldErrorDetails.of(failure))
        .contains(List.of(new ErrorDetail(QUANTITY, FieldErrorDetails.WRONG_TYPE)));
  }

  @Test
  @DisplayName("a constraint's own message is kept")
  void constraintMessageIsKept() {
    var failure = failedBinding(new MutablePropertyValues().add(QUANTITY, "0"));

    assertThat(FieldErrorDetails.of(failure))
        .contains(List.of(new ErrorDetail(QUANTITY, "must be greater than or equal to 1")));
  }

  @Test
  @DisplayName("a message that would quote the rejected value gets fixed text")
  void messageQuotingValueGetsFixedText() {
    var failure = failedBinding(new MutablePropertyValues().add(ACCOUNT, IBAN));

    assertThat(FieldErrorDetails.of(failure))
        .contains(List.of(new ErrorDetail(ACCOUNT, FieldErrorDetails.NOT_VALID)));
    assertThat(FieldErrorDetails.of(failure).orElseThrow().toString()).doesNotContain(IBAN);
  }

  @Test
  @DisplayName("a rule on the whole object is listed under the rule's name")
  void wholeObjectRuleIsListedUnderItsName() {
    var failure = failedBinding(new MutablePropertyValues().add(ACCOUNT, "XX"));

    assertThat(FieldErrorDetails.of(failure))
        .contains(List.of(new ErrorDetail("allowedAccount", "is not allowed")));
  }

  @Test
  @DisplayName("an error that is not a failed validation gives nothing")
  void otherErrorGivesNothing() {
    assertThat(FieldErrorDetails.of(new IllegalStateException("broken"))).isEmpty();
  }

  @Test
  @DisplayName("a message from a resource bundle that quotes the rejected value gets fixed text")
  void bundleMessageQuotingValueGetsFixedText() {
    var failure = failedBinding(new MutablePropertyValues().add("backupAccount", IBAN));

    assertThat(FieldErrorDetails.of(failure))
        .contains(List.of(new ErrorDetail("backupAccount", FieldErrorDetails.NOT_VALID)));
  }

  @Test
  @DisplayName("a Spring Validator's message that quotes the rejected value gets fixed text")
  void springValidatorMessageQuotingValueGetsFixedText() {
    var binder = new DataBinder(new OrderForm(), "orderForm");
    binder.bind(new MutablePropertyValues().add(ACCOUNT, IBAN));
    binder
        .getBindingResult()
        .rejectValue(ACCOUNT, "account.unknown", "Account " + IBAN + " is not known");

    assertThat(FieldErrorDetails.of(new BindException(binder.getBindingResult())))
        .contains(List.of(new ErrorDetail(ACCOUNT, FieldErrorDetails.NOT_VALID)));
  }

  @Test
  @DisplayName("a map key in the field path, which the caller chose, becomes [*]")
  void mapKeyIsHidden() {
    var failure =
        failedBinding(new MutablePropertyValues().add("quantitiesByCustomer[customer-4711]", "0"));

    assertThat(FieldErrorDetails.of(failure))
        .contains(
            List.of(
                new ErrorDetail("quantitiesByCustomer[*]", "must be greater than or equal to 1")));
  }
}
