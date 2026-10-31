package com.exepex.stratum.web;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Holds a setting per path pattern, such as a time or body size limit, and finds the value of the
 * most specific pattern a request's path matches.
 *
 * @param <T> the type of the values
 */
class RouteValues<T> {

  /** The value of each pattern, the most specific pattern first. */
  private final List<Map.Entry<PathPattern, T>> routes;

  /**
   * Parses the patterns.
   *
   * @param routes the value of each path pattern, such as {@code /reports/**}
   * @throws org.springframework.web.util.pattern.PatternParseException if a pattern is not valid
   */
  RouteValues(Map<String, T> routes) {
    this.routes =
        routes.entrySet().stream()
            .map(
                route ->
                    Map.entry(
                        PathPatternParser.defaultInstance.parse(route.getKey()), route.getValue()))
            .sorted(Map.Entry.comparingByKey(PathPattern.SPECIFICITY_COMPARATOR))
            .toList();
  }

  /**
   * Returns the value of the most specific pattern the path matches.
   *
   * @param pathWithinApplication the request's path, without the application's context path
   * @return the value, or empty when no pattern matches
   */
  Optional<T> find(String pathWithinApplication) {
    var path = PathContainer.parsePath(pathWithinApplication);
    return routes.stream()
        .filter(route -> route.getKey().matches(path))
        .map(Map.Entry::getValue)
        .findFirst();
  }
}
