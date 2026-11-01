package com.exepex.stratum.core.condition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName("ModulesLeftOffNotice lists, in one line, the Stratum modules present but off")
@ExtendWith(OutputCaptureExtension.class)
class ModulesLeftOffNoticeTest {

  private static final String NOTICE = "Stratum modules present but off";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(OrdersModule.class, OrdersFormatModule.class, AuditBean.class);

  /** Sends the started event of the context to the notice, as Spring Boot does after startup. */
  private void announceStarted(AssertableApplicationContext context) {
    new ModulesLeftOffNotice()
        .onApplicationEvent(
            new ApplicationStartedEvent(
                new SpringApplication(),
                new String[0],
                context.getSourceApplicationContext(),
                Duration.ZERO));
  }

  @Test
  @DisplayName("with no Stratum module present, nothing is logged")
  void noModulePresent(CapturedOutput output) {
    new ApplicationContextRunner().run(this::announceStarted);

    assertThat(output).doesNotContain(NOTICE);
  }

  @Test
  @DisplayName("with every module on, nothing is logged")
  void allModulesOn(CapturedOutput output) {
    contextRunner
        .withPropertyValues("stratum.orders.enabled=true", "stratum.audit.enabled=true")
        .run(this::announceStarted);

    assertThat(output).doesNotContain(NOTICE);
  }

  @Test
  @DisplayName("with some modules off, one line names each off module once, with its switch")
  void someModulesOff(CapturedOutput output) {
    contextRunner.run(this::announceStarted);

    assertThat(output.getOut().lines().filter(line -> line.contains(NOTICE))).hasSize(1);
    assertThat(output)
        .contains(
            NOTICE + ": audit (stratum.audit.enabled=true), orders (stratum.orders.enabled=true)");
  }

  @Test
  @DisplayName("a module switched on is left out of the line")
  void moduleOnIsLeftOut(CapturedOutput output) {
    contextRunner.withPropertyValues("stratum.orders.enabled=true").run(this::announceStarted);

    assertThat(output)
        .contains(NOTICE + ": audit (stratum.audit.enabled=true)")
        .doesNotContain("orders (");
  }

  /** A module switched on its class. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule("orders")
  static class OrdersModule {}

  /** A second class of the same module, so the module must still be named once. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule("orders")
  static class OrdersFormatModule {}

  /** A module switched on one bean method. */
  @Configuration(proxyBeanMethods = false)
  static class AuditBean {

    /**
     * Returns a bean that exists only while the audit module is on.
     *
     * @return the bean
     */
    @Bean
    @ConditionalOnStratumModule("audit")
    String auditTrail() {
      return "audit";
    }
  }
}
