package com.exepex.stratum.resilience.webmvc;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.request.HealthProbePaths;
import com.exepex.stratum.resilience.LimitedRoute;
import com.exepex.stratum.resilience.RequestRefusedException;
import com.exepex.stratum.resilience.RouteLimits;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Holds each request on a limited route within the route's limit, for the whole request: a
 * synchronous one until the rest of the chain returns, an asynchronous one, such as a {@code
 * Callable}, {@code DeferredResult} or streamed body, until the server completes it. A request over
 * the limit is refused at once with {@code SX-RESILIENCE-0001} and a {@code Retry-After} header,
 * answered by Spring MVC's exception resolvers, so the web starter gives the code's status and
 * Stratum's body; with no resolver answering, it gets 503. Actuator and probe paths and CORS
 * preflight requests are never counted.
 */
@StratumInternal
@RequiredArgsConstructor
public class ConcurrencyLimitFilter extends OncePerRequestFilter {

  /**
   * Runs after Spring Security (-100) and every filter that wraps the request (up to 0), so a
   * request they answer, such as a 401, is not counted, and inside Stratum's web filters, so a
   * refusal carries the request's IDs and its log line.
   */
  public static final int ORDER = 1;

  /** The limit of each route. */
  private final RouteLimits routeLimits;

  /** The actuator and probe paths, which are never counted. */
  private final HealthProbePaths uncountedPaths;

  /** The value of a refusal's {@code Retry-After} header, in seconds. */
  private final String retryAfterSeconds;

  /** Spring MVC's exception resolvers, run in their order, if the service has Spring MVC's. */
  private final ObjectProvider<HandlerExceptionResolver> exceptionResolvers;

  /**
   * Leaves every request alone when no route is limited, the default, and every CORS preflight.
   *
   * @param request the request
   * @return whether the request is passed on uncounted
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return routeLimits.isEmpty() || CorsUtils.isPreFlightRequest(request);
  }

  /**
   * Runs the rest of the chain within the limit of the request's route, or refuses the request; a
   * request on no limited route, or on an actuator or probe path, runs uncounted.
   *
   * @param request the request
   * @param response the response
   * @param filterChain the rest of the chain
   * @throws ServletException if the rest of the chain fails
   * @throws IOException if the rest of the chain, or the refusal, fails to read or write
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    var path = request.getRequestURI().substring(request.getContextPath().length());
    var route = routeLimits.find(path).filter(limited -> !uncountedPaths.contains(path));
    if (route.isEmpty()) {
      filterChain.doFilter(request, response);
    } else if (route.get().tryAdmit()) {
      runAdmitted(route.get(), request, response, filterChain);
    } else {
      refuse(request, response);
    }
  }

  /**
   * Runs the rest of the chain and gives the permit back when the request ends: now, or, once it
   * has gone asynchronous, when the server completes it.
   */
  private void runAdmitted(
      LimitedRoute route,
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain)
      throws ServletException, IOException {
    try {
      filterChain.doFilter(request, response);
    } finally {
      if (request.isAsyncStarted()) {
        request.getAsyncContext().addListener(new PermitRelease(route));
      } else {
        route.release();
      }
    }
  }

  /**
   * Answers a refused request: the {@code Retry-After} header, then the refusal through Spring
   * MVC's resolvers, or 503 when none answers it or one answers with a view, which a filter cannot
   * show.
   */
  private void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setHeader(HttpHeaders.RETRY_AFTER, retryAfterSeconds);
    var resolver = exceptionResolvers.getIfAvailable();
    var answer =
        resolver == null
            ? null
            : resolver.resolveException(request, response, null, new RequestRefusedException());
    if (answer == null || !answer.isEmpty()) {
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }
  }
}
