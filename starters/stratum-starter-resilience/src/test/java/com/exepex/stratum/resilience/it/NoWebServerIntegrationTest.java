package com.exepex.stratum.resilience.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.resilience.ResilienceConstants;
import com.exepex.stratum.resilience.RouteLimits;
import com.exepex.stratum.test.LogLineCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
    classes = LimitedApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ResilienceConstants.INCOMING_ROUTES + "[/reports/**].max-concurrent=5")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("An application with no web server and routes set starts, with the limits inert")
class NoWebServerIntegrationTest {

  @Autowired private ApplicationContext context;

  @Test
  @DisplayName("under @SpringBootTest(webEnvironment = NONE), no limit is made")
  void springBootTestNone() {
    assertThat(context.getBeanNamesForType(RouteLimits.class)).isEmpty();
  }

  @Test
  @DisplayName("with spring.main.web-application-type=none, it starts with no limit and no warning")
  void webApplicationTypeNone(CapturedOutput output) {
    var resilienceLines = new LogLineCapture(ResilienceConstants.class.getPackageName());
    resilienceLines.start();
    try (var started =
        new SpringApplication(LimitedApplication.class)
            .run(
                "--" + ResilienceConstants.INCOMING_ROUTES + "[/reports/**].max-concurrent=5",
                "--spring.main.web-application-type=none")) {
      assertThat(started.getBeanNamesForType(RouteLimits.class)).isEmpty();
    } finally {
      resilienceLines.stop();
    }
    assertThat(resilienceLines.lines()).isEmpty();
    assertThat(output.getOut()).doesNotContain("SX-RESILIENCE");
  }
}
