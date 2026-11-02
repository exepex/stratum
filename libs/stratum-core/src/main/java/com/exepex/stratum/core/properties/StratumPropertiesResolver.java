package com.exepex.stratum.core.properties;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.ErrorDetail;
import com.exepex.stratum.core.error.ErrorReport;
import com.exepex.stratum.core.error.StratumException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.validation.Validator;
import jakarta.validation.metadata.ConstraintDescriptor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Gatherers;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DataObjectPropertyName;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.util.ObjectUtils;

/**
 * Works out the settings a Stratum module uses: the values bound from properties, changed by the
 * service's {@link StratumPropertiesCustomizer} classes listed in {@code
 * META-INF/spring.factories}. Java configuration wins because it is applied last. Only the result
 * is validated, so Java can correct or supply a value that the files leave invalid or unset, and a
 * bad value from either place stops the application at startup.
 *
 * <p>A module calls {@link #resolve} in each startup phase that needs its settings, so the
 * customizers may run more than once and must return the same result every time.
 */
@StratumInternal
@RequiredArgsConstructor
public final class StratumPropertiesResolver {

  /** Attributes every constraint has, which say nothing about its limits. */
  private static final Set<String> NON_LIMIT_ATTRIBUTES = Set.of("message", "groups", "payload");

  /** Shown in place of a map key or list index. */
  private static final String ANY_ELEMENT = "[*]";

  /** Checks the Bean Validation constraints declared on the module's settings type. */
  private final Validator validator;

  /**
   * Returns the settings a module uses: {@code boundProperties}, changed by each customizer in
   * turn.
   *
   * @param boundProperties the settings bound from {@code stratum.<module>.*} properties
   * @param customizers the service's customizers for these settings, in the order to apply them; an
   *     module passes the classes {@link StratumCustomizers#load} finds
   * @param <P> the module's settings type
   * @return the settings after every customizer has run
   * @throws StratumException {@code SX-CORE-1002} if a customizer is broken
   * @throws StratumException {@code SX-CORE-1003} if the resulting settings break a constraint; its
   *     details name each property key and rule
   */
  public <P> P resolve(
      P boundProperties, Stream<? extends StratumPropertiesCustomizer<P>> customizers) {
    var effectiveProperties =
        customizers
            .gather(Gatherers.fold(() -> boundProperties, this::customize))
            .findFirst()
            .orElseThrow();
    validate(effectiveProperties);
    return effectiveProperties;
  }

  /**
   * Describes the violations in one sorted line, so that the message is the same on every run. It
   * names each property key and rule, such as {@code @Size(max=10)}, but never a value, map key,
   * list index or constraint message, because any of them can be a secret.
   *
   * @param properties the settings that were validated
   * @param violations the constraints they break
   * @param <P> the module's settings type
   * @return the broken rules, such as {@code "stratum.example.name breaks @NotBlank;
   *     stratum.example.timeout breaks @NotNull"}
   */
  <P> String describeViolations(P properties, Set<ConstraintViolation<P>> violations) {
    return describe(brokenRules(properties, violations));
  }

  /**
   * Returns one detail per violation, sorted: the property key as the field and the rule as the
   * message.
   */
  private <P> List<ErrorDetail> brokenRules(P properties, Set<ConstraintViolation<P>> violations) {
    var prefix = prefixOf(properties.getClass());
    return violations.stream()
        .map(violation -> brokenRule(prefix, violation))
        .sorted(Comparator.comparing(ErrorDetail::field).thenComparing(ErrorDetail::message))
        .toList();
  }

  /** Joins broken rules into one line, such as {@code "stratum.example.name breaks @NotBlank"}. */
  private String describe(List<ErrorDetail> brokenRules) {
    return brokenRules.stream()
        .map(brokenRule -> brokenRule.field() + " breaks " + brokenRule.message())
        .collect(Collectors.joining("; "));
  }

  /**
   * Runs one customizer and rejects a result the module cannot use, naming the customizer at fault.
   */
  private <P> P customize(P properties, StratumPropertiesCustomizer<P> customizer) {
    var customizerName = customizer.getClass().getName();
    var settingsType = properties.getClass().getSimpleName();
    P customized;
    try {
      customized = customizer.customize(properties);
    } catch (ClassCastException castFailure) {
      // Java erases generic types, so the customizer may be for other settings.
      throw new StratumException(
          CoreErrorCode.CUSTOMIZER_BROKEN,
          customizerName
              + " failed with a ClassCastException while changing "
              + settingsType
              + ". If it is for other settings, "
              + declareWithType(settingsType)
              + " Otherwise see the cause.",
          castFailure);
    }
    if (!properties.getClass().isInstance(customized)) {
      throw new StratumException(
          CoreErrorCode.CUSTOMIZER_BROKEN,
          customizerName
              + " returned null or settings of another type. Return the settings it received to"
              + " keep them, and "
              + declareWithType(settingsType));
    }
    return customized;
  }

  /** Tells the developer how to declare a customizer so that it reaches the right module. */
  private String declareWithType(String settingsType) {
    return "declare it as StratumPropertiesCustomizer<"
        + settingsType
        + ">, by implementing the module's customizer interface for these settings.";
  }

  /**
   * Stops startup with {@code SX-CORE-1003} when the settings break a constraint, naming each
   * property key and rule but never a value, which may be a secret.
   */
  private <P> void validate(P properties) {
    var violations = validator.validate(properties);
    if (!violations.isEmpty()) {
      var brokenRules = brokenRules(properties, violations);
      throw new StratumException(
          ErrorReport.of(CoreErrorCode.INVALID_SETTINGS, describe(brokenRules))
              .withDetails(brokenRules));
    }
  }

  /**
   * Names one broken constraint by its property key and its rule.
   *
   * <p>A constraint on the settings type itself has an empty path and is named by the prefix alone.
   */
  private ErrorDetail brokenRule(String prefix, ConstraintViolation<?> violation) {
    var key = propertyKey(violation.getPropertyPath());
    return new ErrorDetail(
        key.isEmpty() ? prefix : prefix + "." + key,
        ruleWithLimits(violation.getConstraintDescriptor()));
  }

  /** Returns the prefix that {@code @ConfigurationProperties} gives the settings type. */
  private String prefixOf(Class<?> type) {
    return MergedAnnotations.from(type).get(ConfigurationProperties.class).getString("prefix");
  }

  /**
   * Builds the property key from the names along the path, in kebab-case, with {@code [*]} for
   * every map key and list index.
   */
  private String propertyKey(Path path) {
    var key =
        StreamSupport.stream(path.spliterator(), false)
            .map(this::keyPart)
            .collect(Collectors.joining());
    return key.startsWith(".") ? key.substring(1) : key;
  }

  /** Returns one node's part of the key: {@code [*]} if it is an element, then {@code .name}. */
  private String keyPart(Path.Node node) {
    var element = node.isInIterable() ? ANY_ELEMENT : "";
    var name = node.getName();
    return node.getKind() == ElementKind.CONTAINER_ELEMENT || name == null
        ? element
        : element + "." + DataObjectPropertyName.toDashedForm(name);
  }

  /**
   * Names a rule by its annotation and the limits set on it, leaving out attributes at their
   * default.
   */
  private String ruleWithLimits(ConstraintDescriptor<?> constraint) {
    var constraintType = constraint.getAnnotation().annotationType();
    var attributeValuesByName = constraint.getAttributes();
    var limits =
        Arrays.stream(constraintType.getDeclaredMethods())
            .filter(attribute -> isLimit(attribute, attributeValuesByName))
            .sorted(Comparator.comparing(Method::getName))
            .map(
                attribute ->
                    attribute.getName()
                        + "="
                        + ObjectUtils.nullSafeToString(
                            attributeValuesByName.get(attribute.getName())))
            .collect(Collectors.joining(", "));
    var ruleName = "@" + constraintType.getSimpleName();
    return limits.isEmpty() ? ruleName : ruleName + "(" + limits + ")";
  }

  /** Returns whether an attribute is a limit set away from its default, such as {@code max}. */
  private boolean isLimit(Method attribute, Map<String, Object> attributeValuesByName) {
    return !NON_LIMIT_ATTRIBUTES.contains(attribute.getName())
        && !Objects.deepEquals(
            attributeValuesByName.get(attribute.getName()), attribute.getDefaultValue());
  }
}
