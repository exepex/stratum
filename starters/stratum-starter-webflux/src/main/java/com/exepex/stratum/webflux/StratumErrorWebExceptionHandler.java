package com.exepex.stratum.webflux;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.web.BootErrorBodyCounter;
import com.exepex.stratum.web.ReferencePage;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.autoconfigure.web.WebProperties.Resources;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webflux.autoconfigure.error.DefaultErrorWebExceptionHandler;
import org.springframework.boot.webflux.error.ErrorAttributes;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.resource.ResourceWebHandler;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;

/**
 * Spring Boot's error handler for WebFlux, which answers every request that failed outside the
 * service's own exception handlers, with three changes: Stratum's body is sent as {@code
 * application/problem+json}, while Spring Boot's body keeps its media type, a browser's default
 * page is Stratum's {@link ReferencePage}, and a failure with an error code is not logged a second
 * time. The service's error templates, such as {@code templates/error/404.html}, still win, as in
 * Spring Boot. Each JSON body that kept Spring Boot's format in backward-compatible mode is counted
 * with the route of the service's handler.
 */
@StratumInternal
public class StratumErrorWebExceptionHandler extends DefaultErrorWebExceptionHandler {

  /**
   * Whether the bodies are Stratum's, so that a service's own {@link ErrorAttributes} keep Spring
   * Boot's media type.
   */
  private final boolean stratumBodies;

  /** Counts Spring Boot's bodies, when the service has Micrometer. */
  private final ObjectProvider<BootErrorBodyCounter> bootErrorBodyCounter;

  /**
   * Makes the handler, as Spring Boot makes its own.
   *
   * @param errorAttributes the error attributes, Stratum's or the service's
   * @param resources where static error pages are looked for
   * @param errorProperties Spring Boot's error settings, such as whether the default page is on
   * @param applicationContext the application's context, where error templates are looked for
   * @param bootErrorBodyCounter counts Spring Boot's bodies, when the service has Micrometer
   */
  public StratumErrorWebExceptionHandler(
      ErrorAttributes errorAttributes,
      Resources resources,
      ErrorProperties errorProperties,
      ApplicationContext applicationContext,
      ObjectProvider<BootErrorBodyCounter> bootErrorBodyCounter) {
    super(errorAttributes, resources, errorProperties, applicationContext);
    this.stratumBodies = errorAttributes instanceof StratumErrorAttributes;
    this.bootErrorBodyCounter = bootErrorBodyCounter;
  }

  /**
   * Returns the error body as JSON, with the Problem Details media type when the body is Stratum's,
   * and counts it when it is Spring Boot's.
   *
   * @param request the failed request
   * @return the response
   */
  @Override
  protected Mono<ServerResponse> renderErrorResponse(ServerRequest request) {
    if (!stratumBodies) {
      return super.renderErrorResponse(request);
    }
    var body = getErrorAttributes(request, ErrorAttributeOptions.defaults());
    if (request.attribute(StratumErrorAttributes.BODY_ATTRIBUTE).isEmpty()) {
      bootErrorBodyCounter.ifAvailable(counter -> counter.count(routeOf(request)));
      return super.renderErrorResponse(request);
    }
    return ServerResponse.status(getHttpStatus(body))
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .bodyValue(body);
  }

  /**
   * Returns the service's error page for a browser, as Spring Boot does, or Stratum's body when
   * there is none and Spring Boot's default page is off, so the mapped status of a failure with an
   * error code is kept. Spring Boot would answer that case with 500.
   *
   * @param request the failed request
   * @return the response
   */
  @Override
  protected Mono<ServerResponse> renderErrorView(ServerRequest request) {
    var page = super.renderErrorView(request);
    if (!stratumBodies) {
      return page;
    }
    var failure = getError(request);
    return page.onErrorResume(failure::equals, pageFailure -> renderErrorResponse(request));
  }

  /**
   * Returns Stratum's reference page, which replaces Spring Boot's whitelabel page. The page needs
   * Stratum's body, so a service's own {@link ErrorAttributes} keep Spring Boot's page.
   *
   * @param responseBody the response with its status set
   * @param error the error body's members
   * @return the response
   */
  @Override
  protected Mono<ServerResponse> renderDefaultErrorView(
      ServerResponse.BodyBuilder responseBody, Map<String, Object> error) {
    if (!stratumBodies) {
      return super.renderDefaultErrorView(responseBody, error);
    }
    return responseBody
        .contentType(MediaType.parseMediaType(ReferencePage.CONTENT_TYPE))
        .bodyValue(ReferencePage.of(error, MDC.get(LoggingContextConstants.TRACE_ID)));
  }

  /**
   * Logs the failure as Spring Boot does, except a failure with an error code that Stratum's error
   * attributes answered, which logged it already.
   *
   * @param request the failed request
   * @param response the response
   * @param throwable what the request failed with
   */
  @Override
  protected void logError(ServerRequest request, ServerResponse response, Throwable throwable) {
    if (request.attribute(StratumErrorAttributes.LOGGED_ATTRIBUTE).isEmpty()) {
      super.logError(request, response, throwable);
    }
  }

  /**
   * Returns the route of the service's handler that the request matched, or {@code null} for none.
   * A static resource's catch-all route, which answers an unknown path, counts as none.
   */
  private @Nullable String routeOf(ServerRequest request) {
    var exchange = request.exchange();
    return exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
                instanceof PathPattern route
            && !(exchange.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE)
                instanceof ResourceWebHandler)
        ? route.getPatternString()
        : null;
  }
}
