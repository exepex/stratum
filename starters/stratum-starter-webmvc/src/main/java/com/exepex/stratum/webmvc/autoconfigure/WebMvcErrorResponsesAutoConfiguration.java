package com.exepex.stratum.webmvc.autoconfigure;

import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.properties.StratumCustomizers;
import com.exepex.stratum.web.ErrorResponseFactory;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import com.exepex.stratum.web.WebPropertiesCustomizer;
import com.exepex.stratum.webmvc.ErrorBodyAdvice;
import com.exepex.stratum.webmvc.ErrorTemplateMissingCondition;
import com.exepex.stratum.webmvc.ReferenceErrorPage;
import com.exepex.stratum.webmvc.RequestIdsFilter;
import com.exepex.stratum.webmvc.StratumErrorAttributes;
import com.exepex.stratum.webmvc.StratumExceptionHandler;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.SearchStrategy;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.View;

/**
 * Creates Stratum's error responses for Spring MVC, when {@code stratum.web.enabled=true}. Ordered
 * before Spring Boot's {@code ErrorMvcAutoConfiguration}, because its error attributes and error
 * page replace Boot's.
 */
@AutoConfiguration(before = ErrorMvcAutoConfiguration.class)
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(WebConstants.MODULE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class WebMvcErrorResponsesAutoConfiguration {

  /**
   * Stops startup with {@code SX-CORE-1004} when the service declares a web customizer as a bean,
   * which would be silently ignored.
   *
   * @param beanFactory the service's beans
   */
  public WebMvcErrorResponsesAutoConfiguration(ListableBeanFactory beanFactory) {
    StratumCustomizers.rejectBeans(beanFactory);
  }

  /**
   * Makes every error body. Its settings come from {@link WebProperties#resolve}, so the service's
   * {@link WebPropertiesCustomizer} classes win.
   *
   * @param environment the application's environment
   * @return the factory
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public ErrorResponseFactory errorResponseFactory(Environment environment) {
    return new ErrorResponseFactory(WebProperties.resolve(environment));
  }

  /**
   * Answers a {@code StratumException} thrown by a controller, after the service's own handlers.
   *
   * @param errorResponseFactory makes the error body
   * @return the handler
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public StratumExceptionHandler stratumExceptionHandler(
      ErrorResponseFactory errorResponseFactory) {
    return new StratumExceptionHandler(errorResponseFactory);
  }

  /**
   * Adds the request's IDs to every Problem Details body, and gives the error controller's JSON the
   * Problem Details media type.
   *
   * @param errorResponseFactory adds the IDs
   * @return the advice
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public ErrorBodyAdvice errorBodyAdvice(ErrorResponseFactory errorResponseFactory) {
    return new ErrorBodyAdvice(errorResponseFactory);
  }

  /**
   * Keeps each request's trace and correlation IDs for its error page, which runs after tracing's
   * scope has closed.
   *
   * @return the filter's registration, just inside Spring Boot's tracing filter
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean(name = "stratumRequestIdsFilter")
  public FilterRegistrationBean<RequestIdsFilter> stratumRequestIdsFilter() {
    var registration = new FilterRegistrationBean<>(new RequestIdsFilter());
    registration.setOrder(RequestIdsFilter.ORDER);
    return registration;
  }

  /**
   * Gives every request that failed outside a controller's handling Stratum's error body, through
   * Spring Boot's error controller. It replaces Spring Boot's error attributes, and backs off when
   * the service declares its own.
   *
   * @param errorResponseFactory makes the error body
   * @return the error attributes
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean(value = ErrorAttributes.class, search = SearchStrategy.CURRENT)
  public StratumErrorAttributes stratumErrorAttributes(ErrorResponseFactory errorResponseFactory) {
    return new StratumErrorAttributes(errorResponseFactory);
  }

  /**
   * Stratum's HTML error page, when the service has no error template of its own, has not turned
   * Spring Boot's default page off with {@code spring.web.error.whitelabel.enabled=false}, and has
   * no {@link ErrorAttributes} of its own, whose body the page could not show. This nested class is
   * read before the outer class's beans, so the condition sees only the service's own.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnBooleanProperty(name = "spring.web.error.whitelabel.enabled", matchIfMissing = true)
  @Conditional(ErrorTemplateMissingCondition.class)
  @ConditionalOnMissingBean(value = ErrorAttributes.class, search = SearchStrategy.CURRENT)
  public static class ReferenceErrorPageConfiguration {

    /**
     * The page, named {@code error} like Spring Boot's whitelabel page, which it replaces.
     *
     * @return the page
     */
    @Bean(name = "error")
    @Fallback
    @ConditionalOnMissingBean(name = "error")
    public View referenceErrorPage() {
      return new ReferenceErrorPage();
    }
  }
}
