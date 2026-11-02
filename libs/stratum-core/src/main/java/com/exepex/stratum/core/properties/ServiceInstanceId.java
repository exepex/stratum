package com.exepex.stratum.core.properties;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.StringUtils;

/**
 * The ID of one running instance of the service, the same in its log lines, spans and metrics: the
 * service's own {@code service.instance.id} when it sets one, else a random ID made once per JVM.
 *
 * @param value the ID, such as {@code k3x9q2m7ab}
 */
@StratumInternal
public record ServiceInstanceId(String value) {

  /** The number of characters of a generated ID. */
  private static final int GENERATED_LENGTH = 10;

  /** Digits and lowercase letters: {@link Character#forDigit} writes base 36 with these. */
  private static final int GENERATED_RADIX = 36;

  /** Separates the attributes in {@code OTEL_RESOURCE_ATTRIBUTES}. */
  private static final String ATTRIBUTE_SEPARATOR = ",";

  /** Separates an attribute's key from its value. */
  private static final char KEY_VALUE_SEPARATOR = '=';

  /** Draws the random characters of the generated ID. */
  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * The ID for this JVM when the service sets none, drawn when this class first loads. Only the
   * logging and observability modules use this class, and only while they are on.
   */
  private static final String GENERATED_IN_THIS_JVM = generate();

  /**
   * Returns the instance ID: Spring Boot's {@code
   * management.opentelemetry.resource-attributes[service.instance.id]}, else {@code
   * service.instance.id} in the {@code OTEL_RESOURCE_ATTRIBUTES} environment variable, else the ID
   * generated for this JVM. A blank value counts as not set.
   *
   * @param environment the application's environment
   * @return the instance ID
   */
  public static ServiceInstanceId of(ConfigurableEnvironment environment) {
    return new ServiceInstanceId(
        fromSpringBootKey(environment)
            .or(() -> fromResourceAttributesVariable(environment))
            .orElse(GENERATED_IN_THIS_JVM));
  }

  /** Returns the value of Spring Boot's key, bound as Spring Boot binds its attribute map. */
  private static Optional<String> fromSpringBootKey(ConfigurableEnvironment environment) {
    var instanceId =
        Binder.get(environment)
            .bind(
                StratumModuleConstants.RESOURCE_ATTRIBUTES,
                Bindable.mapOf(String.class, String.class))
            .map(attributes -> attributes.get(StratumModuleConstants.SERVICE_INSTANCE_ID))
            .orElse(null);
    return Optional.ofNullable(instanceId).filter(StringUtils::hasText);
  }

  /**
   * Returns the value in the {@code OTEL_RESOURCE_ATTRIBUTES} environment variable, read and
   * decoded as Spring Boot reads it: the last one wins. Spring Boot reads only the real
   * environment, so no other property source is read.
   */
  private static Optional<String> fromResourceAttributesVariable(
      ConfigurableEnvironment environment) {
    var systemEnvironment =
        environment
            .getPropertySources()
            .get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    var variable =
        systemEnvironment == null
            ? null
            : systemEnvironment.getProperty(StratumModuleConstants.OTEL_RESOURCE_ATTRIBUTES);
    return Arrays.stream(
            StringUtils.tokenizeToStringArray(
                variable instanceof String text ? text : null, ATTRIBUTE_SEPARATOR))
        .map(ServiceInstanceId::instanceIdIn)
        .flatMap(Optional::stream)
        .reduce((earlier, later) -> later)
        .filter(StringUtils::hasText);
  }

  /**
   * Returns the decoded value of one {@code key=value} attribute when its key is {@code
   * service.instance.id}. A value that is not valid percent-encoding is skipped, where Spring
   * Boot's OpenTelemetry setup stops startup, so that a service with only logging on still starts.
   */
  private static Optional<String> instanceIdIn(String attribute) {
    var separator = attribute.indexOf(KEY_VALUE_SEPARATOR);
    if (separator <= 0
        || !StratumModuleConstants.SERVICE_INSTANCE_ID.equals(
            attribute.substring(0, separator).trim())) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          StringUtils.uriDecode(attribute.substring(separator + 1).trim(), StandardCharsets.UTF_8));
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }

  /** Returns a new random ID of digits and lowercase letters. */
  private static String generate() {
    return RANDOM
        .ints(GENERATED_LENGTH, 0, GENERATED_RADIX)
        .mapToObj(digit -> Character.toString(Character.forDigit(digit, GENERATED_RADIX)))
        .collect(Collectors.joining());
  }
}
