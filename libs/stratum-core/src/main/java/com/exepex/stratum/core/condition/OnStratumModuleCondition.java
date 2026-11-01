package com.exepex.stratum.core.condition;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.StratumException;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName.Form;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.type.ClassMetadata;
import org.springframework.core.type.MethodMetadata;

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
   * @throws StratumException {@code SX-CORE-1001} if the module name is not in kebab-case; the
   *     detail names the annotated class or method. {@code SX-CORE-1006} if the module is switched
   *     on now but was off when the modules' startup steps ran
   */
  @Override
  public ConditionOutcome getMatchOutcome(
      ConditionContext context, AnnotatedTypeMetadata metadata) {
    var module = moduleName(metadata);
    var property = StratumModuleConstants.enabledProperty(module);
    var message = ConditionMessage.forCondition(ConditionalOnStratumModule.class, module);
    var enabled = StratumModuleSwitch.isEnabled(context.getEnvironment(), module);
    if (enabled) {
      requireSwitchReadAtStartup(context.getEnvironment(), property);
    }
    return new ConditionOutcome(
        enabled, message.because(property + (enabled ? " is true" : " is not true, so it is off")));
  }

  /**
   * Stops startup when the module is on now but was not on when the modules' startup steps ran, so
   * it would run without its startup defaults and checks. Without the record of {@link
   * EarlyModuleSwitches}, as in a test that starts no Spring Boot application, there is nothing to
   * compare, so it does nothing.
   */
  private void requireSwitchReadAtStartup(Environment environment, String property) {
    if (!(environment instanceof ConfigurableEnvironment configurable)) {
      return;
    }
    var record = configurable.getPropertySources().get(EarlyModuleSwitches.RECORD);
    var name = ConfigurationPropertyName.of(property);
    if (record != null
        && record.getProperty(EarlyModuleSwitches.RECORD) instanceof Set<?> modulesOnEarly
        && !modulesOnEarly.contains(name.getElement(1, Form.UNIFORM))) {
      throw new StratumException(
          CoreErrorCode.MODULE_SWITCHED_ON_TOO_LATE,
          property + " in " + sourceOf(configurable, name));
    }
  }

  /** Returns the name of the property source the switch's value comes from, for the message. */
  private String sourceOf(ConfigurableEnvironment environment, ConfigurationPropertyName name) {
    return StreamSupport.stream(ConfigurationPropertySources.get(environment).spliterator(), false)
        .map(source -> source.getConfigurationProperty(name))
        .filter(Objects::nonNull)
        .findFirst()
        .map(ConfigurationProperty::getSource)
        .map(ConfigurationPropertySource::getUnderlyingSource)
        .filter(PropertySource.class::isInstance)
        .map(source -> ((PropertySource<?>) source).getName())
        .orElse("a property source added later");
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
          CoreErrorCode.MODULE_NAME_NOT_KEBAB_CASE,
          "got \"" + module + "\" on " + annotatedElementName(metadata));
    }
    return module;
  }

  /** Names the annotated class, or the method as {@code Class#method}, so the fault is found. */
  private String annotatedElementName(AnnotatedTypeMetadata metadata) {
    return switch (metadata) {
      case ClassMetadata type -> type.getClassName();
      case MethodMetadata method -> method.getDeclaringClassName() + "#" + method.getMethodName();
      default -> metadata.toString();
    };
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
