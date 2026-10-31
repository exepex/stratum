package com.exepex.stratum.web;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.PathContainer;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Decides how long each request may take until its response starts, from {@code
 * stratum.web.timeout.*} and the handler's {@link ResponseTimeout}, and starts its {@link
 * RequestDeadline}. One timer thread serves every request; it only starts the action of a request
 * that ran out of time, which must be quick.
 */
@Slf4j
public class ResponseTimeouts implements AutoCloseable {

  /** The limit of a request whose path matches no route. */
  private final Duration defaultLimit;

  /** The limit of each route, the most specific pattern first. */
  private final List<Map.Entry<PathPattern, Duration>> routeLimits;

  /** The status of a request that ran out of time. */
  private final HttpStatusCode status;

  /** Runs the action of each request that runs out of time. */
  private final ScheduledThreadPoolExecutor timer;

  /**
   * Makes the timeouts and starts their timer thread.
   *
   * @param settings the web settings, whose timeout settings it uses
   * @throws org.springframework.web.util.pattern.PatternParseException if a route is not a valid
   *     path pattern
   */
  public ResponseTimeouts(WebProperties settings) {
    var timeout = settings.timeout();
    this.defaultLimit = timeout.defaultLimit();
    this.routeLimits =
        timeout.routes().entrySet().stream()
            .map(
                route ->
                    Map.entry(
                        PathPatternParser.defaultInstance.parse(route.getKey()), route.getValue()))
            .sorted(Map.Entry.comparingByKey(PathPattern.SPECIFICITY_COMPARATOR))
            .toList();
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
    var path = PathContainer.parsePath(pathWithinApplication);
    var limit =
        routeLimits.stream()
            .filter(route -> route.getKey().matches(path))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(defaultLimit);
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
