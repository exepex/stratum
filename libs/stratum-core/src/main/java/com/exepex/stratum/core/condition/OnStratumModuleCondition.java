package com.exepex.stratum.core.condition;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Decides whether a Stratum module is switched on, for {@link ConditionalOnStratumModule}. Its
 * outcome appears in Spring Boot's condition evaluation report ({@code --debug}), so a developer
 * can see why a module's beans were or were not created.
 */
class OnStratumModuleCondition extends SpringBootCondition {

  /**
   * Whether a module is on when its {@code stratum.<module>.enabled} property is not set: it is
   * not, so that a service switches on each module it uses (ADR 0003).
   */
  static final boolean ENABLED_BY_DEFAULT = false;

  /**
   * Reads {@code stratum.<module>.enabled} and compares it with the default.
   *
   * @param context the context whose environment holds the property
   * @param metadata the annotated class or method
   * @return a match when the module is on, with the reason
   * @throws IllegalStateException if the module name is not in kebab-case
   */
  @Override
  public ConditionOutcome getMatchOutcome(
      ConditionContext context, AnnotatedTypeMetadata metadata) {
    var module = moduleName(metadata);
    var property = StratumModuleConstants.enabledProperty(module);
    var message = ConditionMessage.forCondition(ConditionalOnStratumModule.class, module);
    var configured = Binder.get(context.getEnvironment()).bind(property, Boolean.class);
    if (configured.isBound()) {
      return new ConditionOutcome(
          configured.get(), message.because(property + " is " + configured.get()));
    }
    return new ConditionOutcome(
        ENABLED_BY_DEFAULT, message.because(property + " is not set; the default applies"));
  }

  /**
   * Returns the module name from the annotation and rejects one that is not in kebab-case.
   *
   * @param metadata the annotated class or method
   * @return the module name
   */
  private static String moduleName(AnnotatedTypeMetadata metadata) {
    String module =
        metadata
            .getAnnotations()
            .get(ConditionalOnStratumModule.class)
            .getString(MergedAnnotation.VALUE);
    if (!isKebabCase(module)) {
      throw new IllegalStateException(
          "@ConditionalOnStratumModule needs a lower-case, kebab-case module name"
              + " such as \"http-client\", but got \""
              + module
              + "\".");
    }
    return module;
  }

  /**
   * Returns whether a module name is one kebab-case word, such as {@code http-client}. Spring
   * Boot's property name rules check the characters; a name with a dot or an index has more than
   * one element or an indexed one, and would turn the switch into a nested or list property.
   *
   * @param module the module name
   * @return whether the name can be the middle part of {@code stratum.<module>.enabled}
   */
  private static boolean isKebabCase(String module) {
    if (!ConfigurationPropertyName.isValid(module)
        || module.endsWith("-")
        || module.contains("--")) {
      return false;
    }
    var name = ConfigurationPropertyName.of(module);
    return name.getNumberOfElements() == 1 && !name.isLastElementIndexed();
  }
}
