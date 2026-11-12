package com.exepex.stratum.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.observability.ExceptionFreeSpanExporter;
import com.exepex.stratum.observability.ExceptionFreeSpans;
import com.exepex.stratum.observability.ObservabilityConstants;
import com.exepex.stratum.observability.testsupport.ExportedSpans;
import com.exepex.stratum.observability.testsupport.ObservabilityTestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SpanExporters;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName("ObservabilityAutoConfiguration keeps exception data out of spans, and backs off")
class ExceptionFreeSpansAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      ObservabilityTestData.contextRunner()
          .withBean(SpanExporters.class, () -> SpanExporters.of(new ExportedSpans()));

  @Test
  @DisplayName("with the module on, every exporter Spring Boot's span processor uses is wrapped")
  void wrapsExporters() {
    contextRunner.run(context -> assertUsesStratumBean(context));
  }

  @Test
  @DisplayName("with the module off, the exporters are not wrapped")
  void moduleOffWrapsNothing() {
    contextRunner
        .withPropertyValues(ObservabilityConstants.ENABLED + "=false")
        .run(context -> assertWrapsNothing(context));
  }

  @Test
  @DisplayName("the service's own bean of the same name replaces Stratum's")
  void serviceBeanWins() {
    contextRunner
        .withUserConfiguration(ServiceSpanBeans.class)
        .run(context -> assertUsesServiceBean(context));
  }

  @Test
  @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
  void companyBeanWins() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ValidationAutoConfiguration.class,
                ObservabilityAutoConfiguration.class,
                CompanySpanAutoConfiguration.class))
        .withBean(SpanExporters.class, () -> SpanExporters.of(new ExportedSpans()))
        .run(context -> assertUsesServiceBean(context));
  }

  /** Checks that Stratum's post-processor is there and has wrapped the exporters. */
  private void assertUsesStratumBean(AssertableApplicationContext context) {
    assertThat(context)
        .hasBean(ObservabilityConstants.EXCEPTION_FREE_SPANS)
        .hasSingleBean(ExceptionFreeSpans.class);
    assertExportersWrapped(context, true);
  }

  /** Checks that no post-processor of Stratum's is there and the exporters are not wrapped. */
  private void assertWrapsNothing(AssertableApplicationContext context) {
    assertThat(context).doesNotHaveBean(ExceptionFreeSpans.class);
    assertExportersWrapped(context, false);
  }

  /** Checks that the service's post-processor is used and the exporters are not wrapped. */
  private void assertUsesServiceBean(AssertableApplicationContext context) {
    assertWrapsNothing(context);
    assertThat(context.getBean(ObservabilityConstants.EXCEPTION_FREE_SPANS))
        .isSameAs(ServiceSpanBeans.POST_PROCESSOR);
  }

  /** Checks whether every exporter in the context's {@code SpanExporters} bean is wrapped. */
  private void assertExportersWrapped(AssertableApplicationContext context, boolean wrapped) {
    assertThat(context.getBean(SpanExporters.class).list())
        .isNotEmpty()
        .allMatch(exporter -> exporter instanceof ExceptionFreeSpanExporter == wrapped);
  }

  /**
   * The service's own post-processor, under Stratum's bean name, which leaves spans as they are.
   */
  @Configuration(proxyBeanMethods = false)
  static class ServiceSpanBeans {

    /** The service's post-processor. */
    static final BeanPostProcessor POST_PROCESSOR = new BeanPostProcessor() {};

    /**
     * Returns the service's post-processor.
     *
     * @return the post-processor
     */
    @Bean(ObservabilityConstants.EXCEPTION_FREE_SPANS)
    static BeanPostProcessor serviceExceptionFreeSpans() {
      return POST_PROCESSOR;
    }
  }

  /** A company framework's auto-configuration that declares no order, with the same bean. */
  @AutoConfiguration
  static class CompanySpanAutoConfiguration extends ServiceSpanBeans {}
}
