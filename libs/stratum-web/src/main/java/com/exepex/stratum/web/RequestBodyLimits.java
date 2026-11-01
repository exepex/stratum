package com.exepex.stratum.web;

import java.util.Optional;
import org.springframework.util.unit.DataSize;
import org.springframework.web.method.HandlerMethod;

/**
 * Decides how large each request's body may be, from {@code stratum.web.body-limit.*} and the
 * handler's {@link RequestBodyLimit}, and starts its {@link RequestBodyCounter}.
 */
public class RequestBodyLimits {

  /** The limit of a request whose path matches no route. */
  private final DataSize defaultLimit;

  /** The limit of each route. */
  private final RouteValues<DataSize> routeLimits;

  /**
   * Makes the limits.
   *
   * @param settings the web settings, whose body limit settings it uses
   * @throws org.springframework.web.util.pattern.PatternParseException if a route is not a valid
   *     path pattern
   */
  public RequestBodyLimits(WebProperties settings) {
    this.defaultLimit = settings.defaultBodyLimit();
    this.routeLimits = new RouteValues<>(settings.bodyLimit().routes());
  }

  /**
   * Starts counting a request's body against the limit of the most specific route its path matches,
   * or the default.
   *
   * @param pathWithinApplication the request's path, without the application's context path
   * @return the request's counter
   */
  public RequestBodyCounter start(String pathWithinApplication) {
    return new RequestBodyCounter(routeLimits.find(pathWithinApplication).orElse(defaultLimit));
  }

  /**
   * Gives the request the limit of its handler's {@link RequestBodyLimit}, if it has one.
   *
   * @param counter the request's counter
   * @param handler the handler chosen for the request
   */
  public void applyHandlerLimit(RequestBodyCounter counter, Object handler) {
    Optional.of(handler)
        .filter(HandlerMethod.class::isInstance)
        .map(method -> ((HandlerMethod) method).getMethodAnnotation(RequestBodyLimit.class))
        .map(annotation -> DataSize.of(annotation.value(), annotation.unit()))
        .ifPresent(counter::limitTo);
  }
}
