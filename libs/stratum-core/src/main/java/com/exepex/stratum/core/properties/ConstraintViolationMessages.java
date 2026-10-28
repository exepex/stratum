package com.exepex.stratum.core.properties;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.validation.metadata.ConstraintDescriptor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DataObjectPropertyName;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.util.ObjectUtils;

/**
 * Turns broken constraints into the one-line startup error a developer reads. It names each
 * property by the key the service sets, such as {@code stratum.greeting.timeout}, and each rule by
 * its annotation and limits, such as {@code @Size(max=10)}. It never includes a value, a map key, a
 * list index or a constraint message, because any of them can be configuration data, and a
 * constraint message can quote the value with {@code ${validatedValue}}.
 */
@UtilityClass
class ConstraintViolationMessages {

  /** Attributes every constraint has, which say nothing about its limits. */
  private static final Set<String> NON_LIMIT_ATTRIBUTES = Set.of("message", "groups", "payload");

  /** Shown in place of a map key or list index. */
  private static final String ANY_ELEMENT = "[*]";

  /**
   * Describes the violations in one sorted line, so that the message is the same on every run.
   *
   * @param properties the settings that were validated
   * @param violations the constraints they break
   * @param <P> the module's settings type
   * @return a message such as {@code "Invalid ExampleProperties: stratum.example.timeout
   *     breaks @NotNull"}
   */
  static <P> String describe(P properties, Set<ConstraintViolation<P>> violations) {
    var prefix = prefixOf(properties.getClass());
    return violations.stream()
        .map(violation -> describe(prefix, violation))
        .sorted()
        .collect(
            Collectors.joining(
                "; ", "Invalid " + properties.getClass().getSimpleName() + ": ", ""));
  }

  /**
   * Names one broken constraint by its property key and its rule.
   *
   * @param prefix the settings type's property prefix, or an empty string if it has none
   * @param violation the broken constraint
   * @return the key and the rule, such as {@code "stratum.example.timeout breaks @NotNull"}
   */
  private static String describe(String prefix, ConstraintViolation<?> violation) {
    var key = propertyKey(violation.getPropertyPath());
    return (prefix.isEmpty() ? key : prefix + "." + key)
        + " breaks "
        + rule(violation.getConstraintDescriptor());
  }

  /**
   * Returns the prefix that {@code @ConfigurationProperties} gives the settings type.
   *
   * @param type the settings type
   * @return the prefix, such as {@code stratum.example}, or an empty string if the type has none
   */
  private static String prefixOf(Class<?> type) {
    return MergedAnnotations.from(type)
        .get(ConfigurationProperties.class)
        .getValue("prefix", String.class)
        .orElse("");
  }

  /**
   * Builds the property key from the names along the path, in kebab-case, with {@code [*]} for
   * every map key and list index.
   *
   * @param path the path to the broken value, such as {@code tokens[alice].<map value>}
   * @return the key, such as {@code tokens[*]}
   */
  private static String propertyKey(Path path) {
    var key = new StringBuilder();
    for (Path.Node node : path) {
      if (node.isInIterable()) {
        key.append(ANY_ELEMENT);
      }
      if (node.getKind() != ElementKind.CONTAINER_ELEMENT && node.getName() != null) {
        key.append(key.isEmpty() ? "" : ".")
            .append(DataObjectPropertyName.toDashedForm(node.getName()));
      }
    }
    return key.toString();
  }

  /**
   * Names a rule by its annotation and the limits set on it, leaving out attributes at their
   * default. The values come from the descriptor that the validator has already read, so a
   * constraint declared without public access works too.
   *
   * @param constraint the broken constraint
   * @return the rule, such as {@code @NotBlank} or {@code @Size(max=10, min=2)}
   */
  private static String rule(ConstraintDescriptor<?> constraint) {
    var type = constraint.getAnnotation().annotationType();
    var values = constraint.getAttributes();
    var limits = new TreeMap<String, String>();
    for (Method attribute : type.getDeclaredMethods()) {
      var value = values.get(attribute.getName());
      if (!NON_LIMIT_ATTRIBUTES.contains(attribute.getName())
          && !Objects.deepEquals(value, attribute.getDefaultValue())) {
        limits.put(attribute.getName(), limit(value));
      }
    }
    var name = "@" + type.getSimpleName();
    return limits.isEmpty() ? name : name + "(" + join(limits) + ")";
  }

  /**
   * Writes one limit's value, naming a class by its simple name.
   *
   * @param value the attribute's value
   * @return the value as text, such as {@code 10} or {@code String}
   */
  private static String limit(@Nullable Object value) {
    return value instanceof Class<?> type
        ? type.getSimpleName()
        : ObjectUtils.nullSafeToString(value);
  }

  /**
   * Joins the limits as {@code name=value} pairs.
   *
   * @param limits the limits, sorted by name
   * @return the pairs, such as {@code max=10, min=2}
   */
  private static String join(Map<String, String> limits) {
    return limits.entrySet().stream()
        .map(limit -> limit.getKey() + "=" + limit.getValue())
        .collect(Collectors.joining(", "));
  }
}
