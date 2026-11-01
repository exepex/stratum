package com.exepex.stratum.core.version;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("SpringBootVersionCheck warns once when Spring Boot is outside the supported line")
@ExtendWith(OutputCaptureExtension.class)
class SpringBootVersionCheckTest {

  private static final String SUPPORTED = "4.1.2";

  private static final String WARNING_CODE = "SX-CORE-1005";

  /** Patches of the supported line, and the supported version itself. */
  private static final String[] VERSIONS_IN_THE_LINE = {"4.1.0", "4.1.2", "4.1.9", "4.1.10"};

  /**
   * Versions outside the line: older, newer, a minor that starts with the same digit, a release
   * candidate.
   */
  private static final String[] VERSIONS_OUTSIDE_THE_LINE = {
    "4.0.5", "4.2.0", "4.10.0", "5.0.0-RC1"
  };

  /** Runs the check for a running version and writes out its log lines. */
  private void runCheck(@Nullable String runningVersion) {
    var logs = new DeferredLogs();
    new SpringBootVersionCheck(logs, SUPPORTED, runningVersion)
        .postProcessEnvironment(new MockEnvironment(), new SpringApplication());
    logs.switchOverAll();
  }

  @Nested
  @DisplayName("with a version in the supported line")
  class InTheLine {

    @ParameterizedTest(name = "{0}")
    @FieldSource("com.exepex.stratum.core.version.SpringBootVersionCheckTest#VERSIONS_IN_THE_LINE")
    @DisplayName("nothing is logged")
    void logsNothing(String runningVersion, CapturedOutput output) {
      runCheck(runningVersion);

      assertThat(output).doesNotContain(WARNING_CODE);
    }
  }

  @Nested
  @DisplayName("with a version outside the supported line")
  class OutsideTheLine {

    @ParameterizedTest(name = "{0}")
    @FieldSource(
        "com.exepex.stratum.core.version.SpringBootVersionCheckTest#VERSIONS_OUTSIDE_THE_LINE")
    @DisplayName("one warning names the version found, the supported line and where to change it")
    void warnsOnce(String runningVersion, CapturedOutput output) {
      runCheck(runningVersion);

      assertThat(output.getOut().lines().filter(line -> line.contains(WARNING_CODE))).hasSize(1);
      assertThat(output)
          .contains("WARN")
          .contains("found " + runningVersion + ", supported 4.1.x")
          .contains("parent POM");
    }
  }

  @Test
  @DisplayName("a version Spring Boot cannot report logs nothing")
  void unknownVersionLogsNothing(CapturedOutput output) {
    runCheck(null);

    assertThat(output).doesNotContain(WARNING_CODE);
  }

  @Test
  @DisplayName(
      "the version the build writes into the jar is the Spring Boot version Stratum builds with")
  void buildWritesTheSpringBootVersion() throws IOException {
    var written =
        PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("META-INF/stratum/spring-boot-version.properties"))
            .getProperty("supported-version");

    assertThat(written).isEqualTo(SpringBootVersion.getVersion());
  }

  @Test
  @DisplayName("the running application, built with the supported version, logs nothing")
  void runningApplicationLogsNothing(CapturedOutput output) {
    var logs = new DeferredLogs();
    new SpringBootVersionCheck(logs)
        .postProcessEnvironment(new MockEnvironment(), new SpringApplication());
    logs.switchOverAll();

    assertThat(output).doesNotContain(WARNING_CODE);
  }
}
