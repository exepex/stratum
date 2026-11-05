package com.exepex.stratum.web;

import com.exepex.stratum.core.error.StratumException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PatternParseException;

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
   * @param key the property the patterns come from, named in the error of a wrong pattern
   * @param routes the value of each path pattern, such as {@code /reports/**}
   * @throws StratumException {@code SX-WEB-1009} if a pattern is not valid
   */
  RouteValues(String key, Map<String, T> routes) {
    this.routes =
        routes.entrySet().stream()
            .map(route -> Map.entry(parse(key, route.getKey()), route.getValue()))
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

  /** Parses one pattern, naming the property and the pattern when it is not valid. */
  private PathPattern parse(String key, String pattern) {
    try {
      return PathPatternParser.defaultInstance.parse(pattern);
    } catch (PatternParseException invalidPattern) {
      throw new StratumException(
          WebErrorCode.INVALID_PATH_PATTERN,
          key + " has '" + pattern + "' (" + invalidPattern.getMessage() + ")",
          invalidPattern);
    }
  }
}
