package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.web.RequestDeadline;
import com.exepex.stratum.web.RequestDeadlineAccessor;
import com.exepex.stratum.web.ResponseTimeouts;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers a request that ran out of time before its response started with the timeout status,
 * through Spring Boot's error handling. When the time is up, it interrupts the request's thread, as
 * Tomcat's stuck thread valve does, so a blocking call that honours interrupts stops and the
 * handler fails. A call that ignores interrupts runs on, and {@link ResponseTimeoutInterceptor}
 * fails the request when the handler answers. A request whose response has started is not
 * interrupted. For a handler that returns a {@code Callable} or {@code DeferredResult}, the limit
 * ends when the handler returns. While the request runs, its deadline is on its thread, so code
 * there and the executors that carry the context can read its time left.
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

  /** Where failures are recorded: the service's observation registry, if it has one. */
  private final ObjectProvider<ObservationRegistry> observationRegistry;

  /** Puts the request's deadline on its thread, for its time left. */
  private final RequestDeadlineAccessor deadlines = new RequestDeadlineAccessor();

  /**
   * Runs the rest of the chain within the request's limit. If the chain fails after the time ran
   * out and the response has not started, the request is answered with the timeout status instead
   * of the failure, which is recorded on the request's observation. Spring MVC hands a handler's
   * failure on as a {@link ServletException}; a later filter, such as Spring Security's or the
   * service's own, can also fail with an unchecked one.
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
    var started = new AtomicReference<@Nullable RequestDeadline>();
    var deadline =
        responseTimeouts.start(
            request.getRequestURI().substring(request.getContextPath().length()),
            () -> onExpiry(requestThread, response, started.get()));
    started.set(deadline);
    request.setAttribute(DEADLINE_ATTRIBUTE, deadline);
    deadlines.setValue(deadline);
    try {
      filterChain.doFilter(request, response);
    } catch (ServletException | IOException | RuntimeException failure) {
      endAndClearInterrupt(deadline);
      if (!deadline.hasExpired() || response.isCommitted()) {
        throw failure;
      }
      var timedOut = responseTimeouts.timedOut(deadline);
      request.setAttribute(TIMED_OUT_ATTRIBUTE, timedOut);
      RequestFailureRecorder.recordFailure(observationRegistry, request, timedOut);
      response.resetBuffer();
      response.sendError(timedOut.getStatusCode().value());
    } finally {
      endAndClearInterrupt(deadline);
      request.removeAttribute(DEADLINE_ATTRIBUTE);
      deadlines.setValue();
    }
  }

  /**
   * Interrupts the request's thread, unless its response has started. A started response is never
   * cut off, so its deadline then ends: its time left reads no limit from then on, and statements
   * it still runs are not failed for the passed limit. The deadline is not known yet when the time
   * ran out before the filter got it back, and the response cannot have started then, so the thread
   * is interrupted.
   */
  private void onExpiry(
      Thread requestThread, HttpServletResponse response, @Nullable RequestDeadline deadline) {
    if (deadline == null || !response.isCommitted()) {
      requestThread.interrupt();
    } else {
      deadline.end();
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
