package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.web.RequestLogWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/**
 * Writes one log line when each request has been answered, through {@link RequestLogWriter}. A
 * request whose {@code Callable} or {@code DeferredResult} finishes later writes it once its
 * response is written, on the second, asynchronous dispatch. Static files served write none.
 */
@StratumInternal
@RequiredArgsConstructor
public class RequestLogFilter extends OncePerRequestFilter {

  /** Runs just inside the request fields filter, so the line carries the request fields. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

  /** The request attribute that keeps when the request started, for its asynchronous dispatch. */
  private static final String START_ATTRIBUTE = RequestLogFilter.class.getName() + ".start";

  /** Writes the line. */
  private final RequestLogWriter requestLogWriter;

  /**
   * Runs the rest of the chain, then writes the line once the request has been answered. A request
   * whose chain fails is logged with status 500.
   *
   * @param request the request
   * @param response the response
   * @param filterChain the rest of the chain
   * @throws ServletException if the rest of the chain fails
   * @throws IOException if the rest of the chain fails to read or write
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!requestLogWriter.isLogged(pathWithinApplication(request))) {
      filterChain.doFilter(request, response);
      return;
    }
    var start =
        request.getAttribute(START_ATTRIBUTE) instanceof Long firstDispatchStart
            ? firstDispatchStart
            : System.nanoTime();
    request.setAttribute(START_ATTRIBUTE, start);
    var answered = false;
    try {
      filterChain.doFilter(request, response);
      answered = true;
    } finally {
      var status = answered ? response.getStatus() : HttpStatus.INTERNAL_SERVER_ERROR.value();
      if (!request.isAsyncStarted() && !isStaticFileServed(request, status)) {
        requestLogWriter.write(
            status,
            Duration.ofNanos(System.nanoTime() - start),
            new ServletServerHttpRequest(request).getHeaders(),
            new ServletServerHttpResponse(response).getHeaders());
      }
    }
  }

  /**
   * Returns false, so the line is written when a {@code Callable} or {@code DeferredResult}
   * finishes and its response is written on a second, asynchronous dispatch.
   *
   * @return false
   */
  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  /** Returns the request's path without the application's context path. */
  private String pathWithinApplication(HttpServletRequest request) {
    return request.getRequestURI().substring(request.getContextPath().length());
  }

  /**
   * Returns whether Spring served a static file, such as a script or an image. A path no handler
   * matched also goes to the static file handler, which answers 404, so that is logged.
   */
  private boolean isStaticFileServed(HttpServletRequest request, int status) {
    return request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE)
            instanceof ResourceHttpRequestHandler
        && !HttpStatusCode.valueOf(status).isError();
  }
}
