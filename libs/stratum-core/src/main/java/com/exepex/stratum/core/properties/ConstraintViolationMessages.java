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
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DataObjectPropertyName;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.util.ObjectUtils;

/**
 * Builds the startup error for broken settings: each property key, such as {@code
 * stratum.greeting.timeout}, and rule, such as {@code @Size(max=10)}. It never shows a value, map
 * key, list index or constraint message, because any of them can be a secret.
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
   * @return the broken rules, such as {@code "stratum.example.name breaks @NotBlank;
   *     stratum.example.timeout breaks @NotNull"}; the resolver puts them in {@code SX-CORE-1003}
   */
  static <P> String describe(P properties, Set<ConstraintViolation<P>> violations) {
    var prefix = prefixOf(properties.getClass());
    return violations.stream()
        .map(violation -> describe(prefix, violation))
        .sorted()
        .collect(Collectors.joining("; "));
  }

  /**
   * Names one broken constraint by its property key and its rule.
   *
   * <p>A constraint on the settings type itself has an empty path and is named by the prefix alone.
   */
  private static String describe(String prefix, ConstraintViolation<?> violation) {
    var key = propertyKey(violation.getPropertyPath());
    return (key.isEmpty() ? prefix : prefix + "." + key)
        + " breaks "
        + rule(violation.getConstraintDescriptor());
  }

  /** Returns the prefix that {@code @ConfigurationProperties} gives the settings type. */
  private static String prefixOf(Class<?> type) {
    return MergedAnnotations.from(type).get(ConfigurationProperties.class).getString("prefix");
  }

  /**
   * Builds the property key from the names along the path, in kebab-case, with {@code [*]} for
   * every map key and list index.
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
   * default.
   */
  private static String rule(ConstraintDescriptor<?> constraint) {
    var type = constraint.getAnnotation().annotationType();
    var values = constraint.getAttributes();
    var limits = new TreeMap<String, String>();
    for (Method attribute : type.getDeclaredMethods()) {
      var value = values.get(attribute.getName());
      if (!NON_LIMIT_ATTRIBUTES.contains(attribute.getName())
          && !Objects.deepEquals(value, attribute.getDefaultValue())) {
        limits.put(attribute.getName(), ObjectUtils.nullSafeToString(value));
      }
    }
    var name = "@" + type.getSimpleName();
    return limits.isEmpty() ? name : name + "(" + join(limits) + ")";
  }

  /** Joins the limits as {@code name=value} pairs. */
  private static String join(Map<String, String> limits) {
    return limits.entrySet().stream()
        .map(limit -> limit.getKey() + "=" + limit.getValue())
        .collect(Collectors.joining(", "));
  }
}
