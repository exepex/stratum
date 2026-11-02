package com.exepex.stratum.core.properties.testsupport;

import java.util.Map;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Instance IDs and the environments that set them, shared by the instance ID tests. The keys are
 * spelled out here, so a test fails if Stratum's constants change.
 */
@UtilityClass
public class ServiceInstanceIdTestData {

  /** The shape of a generated ID: ten digits and lowercase letters. */
  public static final Pattern GENERATED_ID = Pattern.compile("[a-z0-9]{10}");

  /** An ID a team sets itself. */
  public static final String TEAM_ID = "orders-blue-1";

  /** Spring Boot's key for the instance ID, in the bracket form. */
  public static final String SPRING_BOOT_KEY =
      "management.opentelemetry.resource-attributes[service.instance.id]";

  /** Spring Boot's key for the instance ID, in the dotted form a properties file can use. */
  public static final String SPRING_BOOT_DOTTED_KEY =
      "management.opentelemetry.resource-attributes.service.instance.id";

  /** OpenTelemetry's environment variable for resource attributes. */
  public static final String RESOURCE_ATTRIBUTES_VARIABLE = "OTEL_RESOURCE_ATTRIBUTES";

  /**
   * Returns an environment whose real environment variables hold only {@code
   * OTEL_RESOURCE_ATTRIBUTES}, with the given value.
   *
   * @param value the variable's value, such as {@code service.instance.id=pod-1}
   * @return the environment
   */
  public static MockEnvironment withResourceAttributesVariable(String value) {
    var environment = new MockEnvironment();
    environment
        .getPropertySources()
        .addLast(
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of(RESOURCE_ATTRIBUTES_VARIABLE, value)));
    return environment;
  }
}
