package com.exepex.stratum.buildrules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * Checks that a starter keeps the dependencies a service must not get from it optional, so removing
 * an {@code <optional>} fails the build. No module depends on such a starter without those
 * libraries, so a test of the classpath has nowhere to run.
 */
@DisplayName("Starters keep the dependencies a service must not get from them optional")
class OptionalDependenciesTest {

  /**
   * Each module and a dependency it declares optional: the resilience starter's Spring MVC with
   * Tomcat, which only a Spring MVC service has, actuator and its health settings, and Micrometer.
   */
  private static final List<Arguments> OPTIONAL_DEPENDENCIES =
      List.of(
          arguments("starters/stratum-starter-resilience", "spring-boot-starter-webmvc"),
          arguments("starters/stratum-starter-resilience", "spring-boot-actuator"),
          arguments("starters/stratum-starter-resilience", "spring-boot-health"),
          arguments("starters/stratum-starter-resilience", "micrometer-core"));

  /** Reads this repository's POMs. */
  private final BomCheck poms = BomCheck.ofRepository();

  @ParameterizedTest(name = "{0}: {1}")
  @FieldSource("OPTIONAL_DEPENDENCIES")
  @DisplayName("each is declared <optional>true</optional>")
  void declaredOptional(String module, String artifactId) {
    var project = poms.pom(BomCheck.REPOSITORY_ROOT.resolve(module));
    var dependency =
        poms.children(poms.child(project, "dependencies"), "dependency")
            .filter(candidate -> artifactId.equals(poms.artifactId(candidate)))
            .findFirst()
            .orElseThrow(() -> new AssertionError(artifactId + " is not a dependency of " + module));

    assertThat(poms.children(dependency, "optional").map(poms::text))
        .as("%s must stay <optional>true</optional> in %s", artifactId, module)
        .containsExactly("true");
  }
}
