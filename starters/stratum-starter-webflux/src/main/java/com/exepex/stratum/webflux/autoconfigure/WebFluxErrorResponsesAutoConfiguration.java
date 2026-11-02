package com.exepex.stratum.webflux.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.properties.StratumCustomizers;
import com.exepex.stratum.web.BootErrorBodyCounter;
import com.exepex.stratum.web.ErrorResponseFactory;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import com.exepex.stratum.web.autoconfigure.BackwardCompatibleModeConfiguration;
import com.exepex.stratum.web.autoconfigure.WebSettingsConfiguration;
import com.exepex.stratum.webflux.ProblemDetailIdsInterceptor;
import com.exepex.stratum.webflux.StratumErrorAttributes;
import com.exepex.stratum.webflux.StratumErrorWebExceptionHandler;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.SearchStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webflux.autoconfigure.error.ErrorWebFluxAutoConfiguration;
import org.springframework.boot.webflux.error.ErrorAttributes;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.result.view.ViewResolver;

/**
 * Creates Stratum's error responses for Spring WebFlux, when {@code stratum.web.enabled=true}.
 * Ordered before Spring Boot's {@code ErrorWebFluxAutoConfiguration}, because its error attributes
 * and error handler replace Boot's.
 */
@StratumInternal
@AutoConfiguration(before = ErrorWebFluxAutoConfiguration.class)
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(WebConstants.MODULE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@Import({WebSettingsConfiguration.class, BackwardCompatibleModeConfiguration.class})
@EnableConfigurationProperties(org.springframework.boot.autoconfigure.web.WebProperties.class)
public final class WebFluxErrorResponsesAutoConfiguration {

  /** Spring Boot's order for its error handler: just before WebFlux's own exception handlers. */
  private static final int ERROR_HANDLER_ORDER = -1;

  /**
   * Stops startup with {@code SX-CORE-1004} when the service declares a web customizer as a bean,
   * which would be silently ignored.
   *
   * @param beanFactory the service's beans
   */
  public WebFluxErrorResponsesAutoConfiguration(ListableBeanFactory beanFactory) {
    StratumCustomizers.rejectBeans(beanFactory);
  }

  /**
   * Makes every error body.
   *
   * @param webProperties the web module's settings
   * @return the factory
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public ErrorResponseFactory errorResponseFactory(WebProperties webProperties) {
    return new ErrorResponseFactory(webProperties);
  }

  /**
   * Gives every request that failed outside the service's exception handlers Stratum's error body.
   * It replaces Spring Boot's error attributes, and backs off when the service declares its own.
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
   * Answers every failed request, as Spring Boot's error handler does, with Stratum's media type,
   * page and logging. It backs off when the service declares its own error handler.
   *
   * @param errorAttributes the error attributes, Stratum's or the service's
   * @param bootWebProperties Spring Boot's web settings
   * @param viewResolvers the service's view resolvers, for its error templates
   * @param codecs the service's codecs
   * @param applicationContext the application's context
   * @param bootErrorBodyCounter counts Spring Boot's bodies, when the service has Micrometer
   * @return the handler
   */
  @Bean
  @Fallback
  @Order(ERROR_HANDLER_ORDER)
  @ConditionalOnMissingBean(value = ErrorWebExceptionHandler.class, search = SearchStrategy.CURRENT)
  public StratumErrorWebExceptionHandler stratumErrorWebExceptionHandler(
      ErrorAttributes errorAttributes,
      org.springframework.boot.autoconfigure.web.WebProperties bootWebProperties,
      ObjectProvider<ViewResolver> viewResolvers,
      ServerCodecConfigurer codecs,
      ApplicationContext applicationContext,
      ObjectProvider<BootErrorBodyCounter> bootErrorBodyCounter) {
    var handler =
        new StratumErrorWebExceptionHandler(
            errorAttributes,
            bootWebProperties.getResources(),
            bootWebProperties.getError(),
            applicationContext,
            bootErrorBodyCounter);
    handler.setViewResolvers(viewResolvers.orderedStream().toList());
    handler.setMessageWriters(codecs.getWriters());
    handler.setMessageReaders(codecs.getReaders());
    return handler;
  }

  /**
   * Adds the request's IDs to every Problem Details body the service's own handlers return.
   *
   * @param errorResponseFactory adds the IDs
   * @return the interceptor
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public ProblemDetailIdsInterceptor problemDetailIdsInterceptor(
      ErrorResponseFactory errorResponseFactory) {
    return new ProblemDetailIdsInterceptor(errorResponseFactory);
  }
}
