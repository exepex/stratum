package com.exepex.stratum.webflux;

import com.exepex.stratum.web.RequestDeadline;
import com.exepex.stratum.web.ResponseTimeout;
import com.exepex.stratum.web.ResponseTimeouts;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Answers a reactive request that ran out of time before its response started with the timeout
 * status, through Spring Boot's error handling. When the time is up, the request's work is
 * cancelled. A response that has started, such as a stream, is not cut off. It also gives the
 * request the limit of its handler's {@link ResponseTimeout} just before the handler runs.
 */
@RequiredArgsConstructor
public class ResponseTimeoutWebFilter implements WebFilter, HandlerStartListener, Ordered {

  /** Runs just inside the request log filter, so the line carries the timeout status. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 6;

  /** The exchange attribute that holds the request's deadline, for its handler's own limit. */
  private static final String DEADLINE_ATTRIBUTE =
      ResponseTimeoutWebFilter.class.getName() + ".deadline";

  /** Decides each request's limit. */
  private final ResponseTimeouts responseTimeouts;

  /**
   * Runs the rest of the chain within the request's limit.
   *
   * @param exchange the request and its response
   * @param chain the rest of the chain
   * @return completes when the request has been answered, or fails with the timeout
   */
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    return Mono.defer(() -> filterWithinLimit(exchange, chain));
  }

  /**
   * Gives the request the limit of its handler's {@link ResponseTimeout}, if it has one.
   *
   * @param exchange the request and its response
   * @param handler the chosen handler
   */
  @Override
  public void onHandlerStart(ServerWebExchange exchange, Object handler) {
    if (exchange.getAttribute(DEADLINE_ATTRIBUTE) instanceof RequestDeadline deadline) {
      responseTimeouts.applyHandlerLimit(deadline, handler);
    }
  }

  /**
   * Returns the order of this filter.
   *
   * @return {@link #ORDER}
   */
  @Override
  public int getOrder() {
    return ORDER;
  }

  /**
   * Starts the request's deadline and runs the chain until it ends or the time is up. The limit
   * ends when the response starts. A {@link TimeoutException} of the service's own is passed on.
   * The expiry hops to another thread, so the response can start in between: whichever of the two
   * comes first wins, and a started response is never cut off by an expiry still on its way.
   */
  private Mono<Void> filterWithinLimit(ServerWebExchange exchange, WebFilterChain chain) {
    var expiry = Sinks.<Boolean>one();
    var startedOrTimedOut = new AtomicBoolean();
    // The error is answered on Reactor's threads, so the single timer thread stays free.
    var deadline =
        responseTimeouts.start(
            exchange.getRequest().getPath().pathWithinApplication().value(),
            () ->
                Schedulers.parallel()
                    .schedule(
                        () -> {
                          if (startedOrTimedOut.compareAndSet(false, true)) {
                            expiry.tryEmitValue(true);
                          }
                        }));
    exchange.getAttributes().put(DEADLINE_ATTRIBUTE, deadline);
    exchange
        .getResponse()
        .beforeCommit(
            () ->
                Mono.fromRunnable(
                    () -> {
                      startedOrTimedOut.set(true);
                      deadline.end();
                    }));
    return chain
        .filter(exchange)
        .timeout(expiry.asMono())
        .onErrorMap(
            failure -> failure instanceof TimeoutException && deadline.hasExpired(),
            failure -> responseTimeouts.timedOut(deadline))
        .doFinally(signal -> deadline.end());
  }
}
