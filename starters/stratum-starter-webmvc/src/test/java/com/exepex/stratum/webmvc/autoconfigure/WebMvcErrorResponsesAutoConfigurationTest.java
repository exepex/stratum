package com.exepex.stratum.webmvc.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.web.ErrorResponseFactory;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import com.exepex.stratum.web.WebPropertiesCustomizer;
import com.exepex.stratum.webmvc.ErrorBodyAdvice;
import com.exepex.stratum.webmvc.FailureWithErrorCodeResolver;
import com.exepex.stratum.webmvc.ReferenceErrorPage;
import com.exepex.stratum.webmvc.StratumErrorAttributes;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.View;

@DisplayName("WebMvcErrorResponsesAutoConfiguration creates the MVC error handling beans")
class WebMvcErrorResponsesAutoConfigurationTest {

  /** The setting that turns the module on. */
  private static final String MODULE_ON = WebConstants.ENABLED + "=true";

  /** The name of the HTML error page bean, which Spring Boot's whitelabel page also uses. */
  private static final String ERROR_VIEW = "error";

  /** A servlet application with Stratum's and Spring Boot's error handling. */
  private final WebApplicationContextRunner contextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  WebMvcErrorResponsesAutoConfiguration.class,
                  JacksonAutoConfiguration.class,
                  DispatcherServletAutoConfiguration.class,
                  ErrorMvcAutoConfiguration.class));

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
                    .hasSingleBean(WebProperties.class)
                    .hasSingleBean(ErrorResponseFactory.class)
                    .hasSingleBean(FailureWithErrorCodeResolver.class)
                    .hasSingleBean(ErrorBodyAdvice.class);
                assertThat(context.getBean(ErrorAttributes.class))
                    .isInstanceOf(StratumErrorAttributes.class);
                assertThat(context.getBean(ERROR_VIEW, View.class))
                    .isInstanceOf(ReferenceErrorPage.class);
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
    private void assertBootErrorHandlingOnly(AssertableWebApplicationContext context) {
      assertThat(context)
          .doesNotHaveBean(ErrorResponseFactory.class)
          .doesNotHaveBean(FailureWithErrorCodeResolver.class);
      assertThat(context.getBean(ErrorAttributes.class))
          .isNotInstanceOf(StratumErrorAttributes.class);
      assertThat(context.getBean(ERROR_VIEW, View.class)).isNotInstanceOf(ReferenceErrorPage.class);
    }

    @Test
    @DisplayName("an application that is not a servlet web application gets nothing")
    void nonServletApplicationGetsNothing() {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(WebMvcErrorResponsesAutoConfiguration.class))
          .withPropertyValues(MODULE_ON)
          .run(context -> assertThat(context).doesNotHaveBean(ErrorResponseFactory.class));
    }

    @Test
    @DisplayName("with spring.web.error.whitelabel.enabled=false, there is no HTML error page")
    void whitelabelOffGivesNoPage() {
      contextRunner
          .withPropertyValues(MODULE_ON, "spring.web.error.whitelabel.enabled=false")
          .run(context -> assertThat(context).doesNotHaveBean(ERROR_VIEW));
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
      contextRunner
          .withPropertyValues(MODULE_ON)
          .withBean(ErrorResponseFactory.class, () -> serviceFactory)
          .withBean(ErrorAttributes.class, () -> serviceErrorAttributes)
          .run(
              context -> {
                assertThat(context.getBean(ErrorResponseFactory.class)).isSameAs(serviceFactory);
                assertThat(context.getBean(ErrorAttributes.class)).isSameAs(serviceErrorAttributes);
              });
    }

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
    void companyBeansWin() {
      new WebApplicationContextRunner()
          .withPropertyValues(MODULE_ON)
          .withConfiguration(
              AutoConfigurations.of(
                  WebMvcErrorResponsesAutoConfiguration.class,
                  JacksonAutoConfiguration.class,
                  DispatcherServletAutoConfiguration.class,
                  ErrorMvcAutoConfiguration.class,
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
                            + ": customizer beans webCustomizer (WebPropertiesCustomizer)"));
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
