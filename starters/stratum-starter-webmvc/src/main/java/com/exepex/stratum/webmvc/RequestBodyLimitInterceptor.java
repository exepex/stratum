package com.exepex.stratum.webmvc;

import com.exepex.stratum.web.RequestBodyCounter;
import com.exepex.stratum.web.RequestBodyLimit;
import com.exepex.stratum.web.RequestBodyLimits;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Gives the request the limit of its handler's {@link RequestBodyLimit} once Spring MVC has chosen
 * the handler, then fails a request whose declared {@code Content-Length} is larger than its limit
 * before the handler reads the body. It registers itself with Spring MVC.
 */
@RequiredArgsConstructor
public class RequestBodyLimitInterceptor implements HandlerInterceptor, WebMvcConfigurer {

  /** Reads the handler's limit. */
  private final RequestBodyLimits requestBodyLimits;

  /**
   * Registers this interceptor for every path.
   *
   * @param registry the interceptors Spring MVC calls
   */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this);
  }

  /**
   * Gives the request its handler's limit, if the handler has one, and checks the declared length.
   *
   * @param request the request
   * @param response the response
   * @param handler the chosen handler
   * @return {@code true}, so the request goes on
   * @throws com.exepex.stratum.web.RequestBodyTooLargeException if the declared length is larger
   *     than the limit
   */
  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (request.getAttribute(RequestBodyLimitFilter.COUNTER_ATTRIBUTE)
        instanceof RequestBodyCounter counter) {
      requestBodyLimits.applyHandlerLimit(counter, handler);
      counter.checkDeclaredLength(request.getContentLengthLong());
    }
    return true;
  }
}
