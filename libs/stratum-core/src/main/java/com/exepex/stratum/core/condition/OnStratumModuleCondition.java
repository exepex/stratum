package com.exepex.stratum.core.condition;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.StratumException;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Decides whether a Stratum module is switched on, for {@link ConditionalOnStratumModule}. Its
 * outcome appears in Spring Boot's condition evaluation report ({@code --debug}), so a developer
 * can see why a module's beans were or were not created.
 */
class OnStratumModuleCondition extends SpringBootCondition {

  /** A module name in kebab-case characters: lower-case letters, digits and hyphens. */
  private static final Pattern KEBAB_CASE_CHARACTERS = Pattern.compile("[a-z0-9-]+");

  /**
   * Reads {@code stratum.<module>.enabled}; a module whose property is not set is off (ADR 0003).
   *
   * @param context the context whose environment holds the property
   * @param metadata the annotated class or method
   * @return a match when the module is on, with the reason
   * @throws StratumException {@code SX-CORE-1001} if the module name is not in kebab-case
   */
  @Override
  public ConditionOutcome getMatchOutcome(
      ConditionContext context, AnnotatedTypeMetadata metadata) {
    var module = moduleName(metadata);
    var property = StratumModuleConstants.enabledProperty(module);
    var message = ConditionMessage.forCondition(ConditionalOnStratumModule.class, module);
    var enabled = StratumModuleSwitch.isEnabled(context.getEnvironment(), module);
    return new ConditionOutcome(
        enabled, message.because(property + (enabled ? " is true" : " is not true, so it is off")));
  }

  /** Returns the module name from the annotation and rejects one that is not in kebab-case. */
  private String moduleName(AnnotatedTypeMetadata metadata) {
    var module =
        metadata
            .getAnnotations()
            .get(ConditionalOnStratumModule.class)
            .getString(MergedAnnotation.VALUE);
    if (!isKebabCase(module)) {
      throw new StratumException(
          CoreErrorCode.MODULE_NAME_NOT_KEBAB_CASE, "got \"" + module + "\"");
    }
    return module;
  }

  /** Returns whether a module name is one kebab-case word, such as {@code http-client}. */
  private boolean isKebabCase(String module) {
    // One pattern for the hyphen rules would nest quantifiers, which SpotBugs rejects as ReDoS.
    return KEBAB_CASE_CHARACTERS.matcher(module).matches()
        && !module.startsWith("-")
        && !module.endsWith("-")
        && !module.contains("--");
  }
}
