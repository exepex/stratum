package com.exepex.stratum.webmvc.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.web.RequestBodyLimits;
import com.exepex.stratum.web.ResponseTimeouts;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.webmvc.testsupport.WebMvcRequestFieldsRunners;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.util.unit.DataSize;

@DisplayName(
    "WebMvcRequestFieldsAutoConfiguration gives a request with no limit set the limits of the mode")
class WebMvcRequestLimitsTest {

  /** The setting that turns the module on. */
  private static final String MODULE_ON = WebConstants.ENABLED + "=true";

  /**
   * The settings, and the time and body size limits of a request whose path no limit is set for:
   * the standard limits with stratum.backward-compatible unset, none with it true.
   */
  private static final List<Arguments> MODES_AND_LIMITS =
      List.of(
          Arguments.of(List.of(), List.of(Duration.ofSeconds(10), DataSize.ofKilobytes(256))),
          Arguments.of(
              List.of("stratum.backward-compatible=true"),
              List.of(Duration.ZERO, DataSize.ofBytes(0))));

  /** A servlet application with Stratum's request fields. */
  private final WebApplicationContextRunner contextRunner =
      WebMvcRequestFieldsRunners.requestFieldsRunner();

  @ParameterizedTest(name = "{0} gives {1}")
  @FieldSource("MODES_AND_LIMITS")
  @DisplayName("are 10s and 256KB, or none with stratum.backward-compatible=true")
  void limitsFollowTheMode(List<String> settings, List<Object> limits) {
    contextRunner
        .withPropertyValues(MODULE_ON)
        .withPropertyValues(settings.toArray(String[]::new))
        .run(
            context ->
                assertThat(
                        List.of(
                            context.getBean(ResponseTimeouts.class).start("/", () -> {}).limit(),
                            context.getBean(RequestBodyLimits.class).start("/").limit()))
                    .isEqualTo(limits));
  }
}
