package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.api.StratumInternal;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.autoconfigure.template.TemplateAvailabilityProviders;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when the service has no {@code error} template of its own, such as {@code
 * templates/error.html} for Thymeleaf, so that Stratum's error page never hides the service's.
 */
@StratumInternal
public class ErrorTemplateMissingCondition extends SpringBootCondition {

  /** The view name of the error page. */
  static final String ERROR_VIEW = "error";

  /**
   * Returns a match when no template engine finds an {@code error} template.
   *
   * @param context the condition's context
   * @param metadata the annotated element
   * @return the outcome
   */
  @Override
  public ConditionOutcome getMatchOutcome(
      ConditionContext context, AnnotatedTypeMetadata metadata) {
    var serviceTemplate =
        new TemplateAvailabilityProviders(context.getClassLoader())
            .getProvider(
                ERROR_VIEW,
                context.getEnvironment(),
                context.getClassLoader(),
                context.getResourceLoader());
    return serviceTemplate == null
        ? ConditionOutcome.match("The service has no error template")
        : ConditionOutcome.noMatch("The service has its own error template");
  }
}
