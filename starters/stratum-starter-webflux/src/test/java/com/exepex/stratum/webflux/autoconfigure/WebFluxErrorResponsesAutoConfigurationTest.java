package com.exepex.stratum.webflux.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.web.ErrorResponseFactory;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import com.exepex.stratum.web.WebPropertiesCustomizer;
import com.exepex.stratum.webflux.ProblemDetailIdsInterceptor;
import com.exepex.stratum.webflux.StratumErrorAttributes;
import com.exepex.stratum.webflux.StratumErrorWebExceptionHandler;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableReactiveWebApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.error.ErrorWebFluxAutoConfiguration;
import org.springframework.boot.webflux.error.DefaultErrorAttributes;
import org.springframework.boot.webflux.error.ErrorAttributes;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.context.annotation.Bean;

@DisplayName("WebFluxErrorResponsesAutoConfiguration creates the WebFlux error handling beans")
class WebFluxErrorResponsesAutoConfigurationTest {

  /** The setting that turns the module on. */
  private static final String MODULE_ON = WebConstants.ENABLED + "=true";

  /** A reactive application with Stratum's and Spring Boot's error handling. */
  private final ReactiveWebApplicationContextRunner contextRunner =
      new ReactiveWebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  WebFluxErrorResponsesAutoConfiguration.class,
                  WebFluxAutoConfiguration.class,
                  ErrorWebFluxAutoConfiguration.class));

  @Nested
  @DisplayName("when the module is switched on or off")
  class ModuleSwitch {

    @Test
    @DisplayName("with the module on, Stratum's error beans replace Spring Boot's")
    void createsBeans() {
      contextRunner
          .withPropertyValues(MODULE_ON)
          .run(
              context -> {
                assertThat(context)
                    .hasSingleBean(ErrorResponseFactory.class)
                    .hasSingleBean(ProblemDetailIdsInterceptor.class)
                    .hasSingleBean(ErrorWebExceptionHandler.class);
                assertThat(context.getBean(ErrorAttributes.class))
                    .isInstanceOf(StratumErrorAttributes.class);
                assertThat(context.getBean(ErrorWebExceptionHandler.class))
                    .isInstanceOf(StratumErrorWebExceptionHandler.class);
              });
    }

    @Test
    @DisplayName("with the module unset or off, Spring Boot's error handling is left as it is")
    void moduleOffCreatesNothing() {
      contextRunner.run(context -> assertBootErrorHandlingOnly(context));
      contextRunner
          .withPropertyValues(WebConstants.ENABLED + "=false")
          .run(context -> assertBootErrorHandlingOnly(context));
    }

    /** Checks that the context has Spring Boot's error beans and none of Stratum's. */
    private void assertBootErrorHandlingOnly(AssertableReactiveWebApplicationContext context) {
      assertThat(context)
          .doesNotHaveBean(ErrorResponseFactory.class)
          .doesNotHaveBean(ProblemDetailIdsInterceptor.class);
      assertThat(context.getBean(ErrorAttributes.class))
          .isNotInstanceOf(StratumErrorAttributes.class);
      assertThat(context.getBean(ErrorWebExceptionHandler.class))
          .isNotInstanceOf(StratumErrorWebExceptionHandler.class);
    }

    @Test
    @DisplayName("an application that is not a reactive web application gets nothing")
    void nonReactiveApplicationGetsNothing() {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(WebFluxErrorResponsesAutoConfiguration.class))
          .withPropertyValues(MODULE_ON)
          .run(context -> assertThat(context).doesNotHaveBean(ErrorResponseFactory.class));
    }
  }

  @Nested
  @DisplayName("when the service or a company framework has its own beans")
  class BackingOff {

    @Test
    @DisplayName("the service's own beans replace Stratum's")
    void serviceBeansWin() {
      var serviceFactory = factoryWithNoStatuses();
      var serviceErrorAttributes = new DefaultErrorAttributes();
      ErrorWebExceptionHandler serviceHandler = (exchange, failure) -> null;
      contextRunner
          .withPropertyValues(MODULE_ON)
          .withBean(ErrorResponseFactory.class, () -> serviceFactory)
          .withBean(ErrorAttributes.class, () -> serviceErrorAttributes)
          .withBean(ErrorWebExceptionHandler.class, () -> serviceHandler)
          .run(
              context -> {
                assertThat(context)
                    .hasSingleBean(ErrorResponseFactory.class)
                    .hasSingleBean(ErrorAttributes.class)
                    .hasSingleBean(ErrorWebExceptionHandler.class);
                assertThat(context.getBean(ErrorResponseFactory.class)).isSameAs(serviceFactory);
                assertThat(context.getBean(ErrorAttributes.class)).isSameAs(serviceErrorAttributes);
                assertThat(context.getBean(ErrorWebExceptionHandler.class))
                    .isSameAs(serviceHandler);
              });
    }

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
    void companyBeansWin() {
      new ReactiveWebApplicationContextRunner()
          .withPropertyValues(MODULE_ON)
          .withConfiguration(
              AutoConfigurations.of(
                  WebFluxErrorResponsesAutoConfiguration.class,
                  WebFluxAutoConfiguration.class,
                  ErrorWebFluxAutoConfiguration.class,
                  CompanyBeansAutoConfiguration.class))
          .run(
              context -> {
                assertThat(context.getBean(ErrorResponseFactory.class))
                    .isSameAs(CompanyBeansAutoConfiguration.FACTORY);
                assertThat(context.getBean(ErrorAttributes.class))
                    .isSameAs(CompanyBeansAutoConfiguration.ERROR_ATTRIBUTES);
              });
    }
  }

  @Test
  @DisplayName("a web customizer declared as a bean stops startup with SX-CORE-1004")
  void customizerBeanStopsStartup() {
    contextRunner
        .withPropertyValues(MODULE_ON)
        .withBean("webCustomizer", WebPropertiesCustomizer.class, () -> settings -> settings)
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(StratumException.class)
                    .hasMessage(
                        "SX-CORE-1004: "
                            + CoreErrorCode.CUSTOMIZER_DECLARED_AS_BEAN.getDescription()
                            + ": customizer beans webCustomizer"));
  }

  /** Returns a factory with no error statuses. */
  private static ErrorResponseFactory factoryWithNoStatuses() {
    return new ErrorResponseFactory(
        WebProperties.builder()
            .error(WebProperties.ErrorSettings.builder().statuses(Map.of()).build())
            .build());
  }

  /** A company framework's auto-configuration that declares no order. */
  @AutoConfiguration
  static class CompanyBeansAutoConfiguration {

    /** The company's factory. */
    static final ErrorResponseFactory FACTORY = factoryWithNoStatuses();

    /** The company's error attributes. */
    static final ErrorAttributes ERROR_ATTRIBUTES = new DefaultErrorAttributes();

    /**
     * Returns the company's factory.
     *
     * @return the factory
     */
    @Bean
    ErrorResponseFactory companyErrorResponseFactory() {
      return FACTORY;
    }

    /**
     * Returns the company's error attributes.
     *
     * @return the error attributes
     */
    @Bean
    ErrorAttributes companyErrorAttributes() {
      return ERROR_ATTRIBUTES;
    }
  }
}
