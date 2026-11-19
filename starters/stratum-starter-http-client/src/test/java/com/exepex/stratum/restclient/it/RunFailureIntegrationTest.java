package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

@ExtendWith(OutputCaptureExtension.class)
@Timeout(30)
@DisplayName(
    "A command-line tool whose call fails after startup gets Spring Boot's run failure with the"
        + " stack trace and the code, not the startup report")
class RunFailureIntegrationTest {

  @Test
  @DisplayName(
      "a CommandLineRunner whose service is down logs \"Application run failed\" once, with"
          + " error.code SX-HTTPC-0002 and the stack trace")
  void runnerCallFailureKeepsStackTrace(CapturedOutput output) {
    var tool =
        new SpringApplicationBuilder(ClientsApplication.class, FailingCallConfiguration.class)
            .web(WebApplicationType.NONE);
    // An application another test left running in this JVM keeps Logback marked as set up, so
    // the tool would keep that application's log format; a real tool has a JVM of its own.
    LoggingSystem.get(ClassUtils.getDefaultClassLoader()).cleanUp();

    // Command-line arguments, as a tool gets them, rank above the test's application.properties,
    // which turns logging off.
    String[] arguments = {
      "--stratum.logging.enabled=true",
      "--spring.application.name=stock-report",
      "--" + RestClientTestData.NO_RETRIES,
      // Nothing listens on this port: the inventory service is down.
      "--"
          + RestClientTestData.setting(
              RestClientTestData.INVENTORY_URL, RestClientTestData.UNUSED_URL),
      "--"
          + RestClientTestData.setting(
              RestClientTestData.PRICING_URL, RestClientTestData.UNUSED_URL)
    };

    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(() -> tool.run(arguments))
        .withMessageStartingWith("SX-HTTPC-0002: ");

    var runFailures =
        output.getOut().lines().filter(line -> line.contains("Application run failed")).toList();
    assertThat(runFailures)
        .singleElement()
        .asString()
        .contains("\"error.code\":\"SX-HTTPC-0002\"")
        .contains("\"stack_trace\":\"")
        .contains(StratumException.class.getName());
    assertThat(output).doesNotContain("APPLICATION FAILED TO START");
  }

  /** A tool that reads the stock once, after startup, as a one-shot job does. */
  @Configuration(proxyBeanMethods = false)
  static class FailingCallConfiguration {

    /**
     * Calls the inventory service once the application has started.
     *
     * @param inventory the inventory service's client
     * @return the runner
     */
    @Bean
    CommandLineRunner stockReport(InventoryClient inventory) {
      return args -> inventory.stock();
    }
  }
}
