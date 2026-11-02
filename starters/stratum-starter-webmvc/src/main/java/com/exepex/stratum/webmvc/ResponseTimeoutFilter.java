package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.web.RequestDeadline;
import com.exepex.stratum.web.ResponseTimeouts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers a request that ran out of time before its response started with the timeout status,
 * through Spring Boot's error handling. When the time is up, it interrupts the request's thread, as
 * Tomcat's stuck thread valve does, so a blocking call that honours interrupts stops and the
 * handler fails. A call that ignores interrupts runs on, and {@link ResponseTimeoutInterceptor}
 * fails the request when the handler answers. A request whose response has started is not
 * interrupted. For a handler that returns a {@code Callable} or {@code DeferredResult}, the limit
 * ends when the handler returns.
 */
@StratumInternal
@RequiredArgsConstructor
public class ResponseTimeoutFilter extends OncePerRequestFilter {

  /** Runs just inside the request log filter, so the line carries the timeout status. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 6;

  /** The request attribute that holds the request's deadline, for its handler's own limit. */
  static final String DEADLINE_ATTRIBUTE = ResponseTimeoutFilter.class.getName() + ".deadline";

  /** The request attribute that holds the exception a timed-out request is answered with. */
  static final String TIMED_OUT_ATTRIBUTE = ResponseTimeoutFilter.class.getName() + ".timedOut";

  /** Decides each request's limit. */
  private final ResponseTimeouts responseTimeouts;

  /**
   * Runs the rest of the chain within the request's limit. If the chain fails after the time ran
   * out and the response has not started, the request is answered with the timeout status instead
   * of the failure. Spring MVC hands a handler's failure on as a {@link ServletException}.
   *
   * @param request the request
   * @param response the response
   * @param filterChain the rest of the chain
   * @throws ServletException if the rest of the chain fails in time
   * @throws IOException if the rest of the chain fails to read or write in time
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    var requestThread = Thread.currentThread();
    var deadline =
        responseTimeouts.start(
            request.getRequestURI().substring(request.getContextPath().length()),
            () -> interruptUnlessStarted(requestThread, response));
    request.setAttribute(DEADLINE_ATTRIBUTE, deadline);
    try {
      filterChain.doFilter(request, response);
    } catch (ServletException | IOException failure) {
      endAndClearInterrupt(deadline);
      if (!deadline.hasExpired() || response.isCommitted()) {
        throw failure;
      }
      var timedOut = responseTimeouts.timedOut(deadline);
      request.setAttribute(TIMED_OUT_ATTRIBUTE, timedOut);
      response.resetBuffer();
      response.sendError(timedOut.getStatusCode().value());
    } finally {
      endAndClearInterrupt(deadline);
      request.removeAttribute(DEADLINE_ATTRIBUTE);
    }
  }

  /** Interrupts the request's thread, unless its response has started. */
  private void interruptUnlessStarted(Thread requestThread, HttpServletResponse response) {
    if (!response.isCommitted()) {
      requestThread.interrupt();
    }
  }

  /**
   * Ends the request's limit and, if it ran out, clears the interrupt, so the server's thread does
   * not carry it into its next request.
   */
  private void endAndClearInterrupt(RequestDeadline deadline) {
    deadline.end();
    if (deadline.hasExpired()) {
      Thread.interrupted();
    }
  }
}
