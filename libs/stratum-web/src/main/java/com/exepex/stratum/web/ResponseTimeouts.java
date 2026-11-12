package com.exepex.stratum.web;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.method.HandlerMethod;

/**
 * Decides how long each request may take until its response starts, from {@code
 * stratum.web.timeout.*} and the handler's {@link ResponseTimeout}, and starts its {@link
 * RequestDeadline}. One timer thread serves every request; it only starts the action of a request
 * that ran out of time, which must be quick.
 */
@Slf4j
public class ResponseTimeouts implements AutoCloseable {

  /** How long a request may take when nothing sets a limit, outside backward-compatible mode. */
  private static final Duration STANDARD_TIMEOUT = Duration.ofSeconds(10);

  /** The limit of a request whose path matches no route. */
  @Getter(AccessLevel.PACKAGE)
  private final Duration defaultLimit;

  /** The limit of each route. */
  private final RouteValues<Duration> routeLimits;

  /** The status of a request that ran out of time. */
  private final HttpStatusCode status;

  /** Runs the action of each request that runs out of time. */
  private final ScheduledThreadPoolExecutor timer;

  /**
   * Makes the timeouts and starts their timer thread.
   *
   * @param settings the web settings, whose timeout settings it uses
   * @param backwardCompatible whether {@code stratum.backward-compatible} is on, which leaves a
   *     request with no limit set unlimited, so an existing service's requests are not cut off;
   *     otherwise such a request may take 10s
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-WEB-1009} if a route is not a
   *     valid path pattern
   */
  public ResponseTimeouts(WebProperties settings, boolean backwardCompatible) {
    var timeout = settings.timeout();
    this.defaultLimit =
        Optional.ofNullable(timeout.defaultLimit())
            .orElse(backwardCompatible ? Duration.ZERO : STANDARD_TIMEOUT);
    this.routeLimits = new RouteValues<>(WebConstants.TIMEOUT_ROUTES, timeout.routes());
    this.status = HttpStatusCode.valueOf(timeout.status());
    this.timer =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().name("stratum-response-timeout").daemon().factory());
    // A request that ends in time cancels its run; without this, each would stay queued until due.
    this.timer.setRemoveOnCancelPolicy(true);
  }

  /**
   * Starts the time limit of a request: the limit of the most specific route its path matches, or
   * the default.
   *
   * @param pathWithinApplication the request's path, without the application's context path
   * @param onExpiry what happens when the request runs out of time; it runs on the timer's thread
   * @return the request's deadline, to end when the request ends
   */
  public RequestDeadline start(String pathWithinApplication, Runnable onExpiry) {
    var limit = routeLimits.find(pathWithinApplication).orElse(defaultLimit);
    var deadline = new RequestDeadline(timer, onExpiry);
    deadline.limitTo(limit);
    return deadline;
  }

  /**
   * Gives the request the limit of its handler's {@link ResponseTimeout}, if it has one.
   *
   * @param deadline the request's deadline
   * @param handler the handler chosen for the request
   */
  public void applyHandlerLimit(RequestDeadline deadline, Object handler) {
    Optional.of(handler)
        .filter(HandlerMethod.class::isInstance)
        .map(method -> ((HandlerMethod) method).getMethodAnnotation(ResponseTimeout.class))
        .map(annotation -> Duration.of(annotation.value(), annotation.unit().toChronoUnit()))
        .ifPresent(deadline::limitTo);
  }

  /**
   * Logs that a request ran out of time, and returns the exception it is answered with.
   *
   * @param deadline the request's deadline
   * @return the exception, which holds the timeout status
   */
  public RequestTimedOutException timedOut(RequestDeadline deadline) {
    if (log.isWarnEnabled()) {
      log.warn(
          "The request ran out of time: its response had not started after {}", deadline.limit());
    }
    return new RequestTimedOutException(status, deadline.limit());
  }

  /** Stops the timer thread when the application stops. */
  @Override
  public void close() {
    timer.shutdownNow();
  }
}
