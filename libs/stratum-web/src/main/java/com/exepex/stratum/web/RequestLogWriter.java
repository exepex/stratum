package com.exepex.stratum.web;

import com.exepex.stratum.core.error.LoggingContextConstants;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the one INFO log line each request ends with: its status, how long it took, the headers
 * the service named and, when turned on, the caller's token payload. The line also carries the
 * request fields, which are in the logging context. Both web starters call it when a request ends.
 */
@Slf4j(topic = WebConstants.REQUEST_LOG_LOGGER)
public final class RequestLogWriter {

  /** The headers that carry credentials, which are never logged. */
  private static final List<String> CREDENTIAL_HEADERS =
      List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.COOKIE, HttpHeaders.SET_COOKIE, "X-Api-Key");

  /** The scheme of a bearer token in the {@code Authorization} header, with its space. */
  private static final String BEARER_PREFIX = "Bearer ";

  /** Splits a JSON Web Token into its header, payload and signature. */
  private static final String TOKEN_PART_SEPARATOR = "\\.";

  /** The number of parts of a signed JSON Web Token. */
  private static final int SIGNED_TOKEN_PARTS = 3;

  /** The nanoseconds in a millisecond. */
  private static final long NANOS_PER_MILLI = Duration.ofMillis(1).toNanos();

  /** Whether requests write the line. */
  private final boolean enabled;

  /** The paths whose requests write no line. */
  private final List<PathPattern> skipPaths;

  /** The request headers to log, by field name, without the denied ones. */
  private final Map<String, String> requestHeaderFields;

  /** The response headers to log, by field name, without the denied ones. */
  private final Map<String, String> responseHeaderFields;

  /** Whether the caller's token claims are logged. */
  private final boolean tokenPayload;

  /** The claims of the caller's token that are logged. */
  private final List<String> tokenClaims;

  /** Makes each value safe for the logs, as the request fields are. */
  private final RequestFieldsReader requestFieldsReader;

  /**
   * Makes the writer.
   *
   * @param settings the web settings
   * @param requestFieldsReader makes each value safe for the logs
   */
  public RequestLogWriter(WebProperties settings, RequestFieldsReader requestFieldsReader) {
    var requestLog = settings.requestLog();
    var deniedHeaders = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    Stream.concat(CREDENTIAL_HEADERS.stream(), requestLog.deniedHeaders().stream())
        .forEach(deniedHeaders::add);
    this.enabled = requestLog.enabled();
    this.skipPaths =
        requestLog.skipPaths().stream().map(PathPatternParser.defaultInstance::parse).toList();
    this.requestHeaderFields = allowedOnly(requestLog.requestHeaders(), deniedHeaders);
    this.responseHeaderFields = allowedOnly(requestLog.responseHeaders(), deniedHeaders);
    this.tokenPayload = requestLog.tokenPayload();
    this.tokenClaims = requestLog.tokenClaims();
    this.requestFieldsReader = requestFieldsReader;
  }

  /**
   * Returns whether a request on this path writes the line.
   *
   * @param pathWithinApplication the request's path, without the application's context path
   * @return true when the line is on and the path is not skipped
   */
  public boolean isLogged(String pathWithinApplication) {
    var path = PathContainer.parsePath(pathWithinApplication);
    return enabled && skipPaths.stream().noneMatch(pattern -> pattern.matches(path));
  }

  /**
   * Writes the line of a request that has been answered, with the request fields that are in the
   * logging context.
   *
   * @param status the response's HTTP status
   * @param duration how long the request took
   * @param requestHeaders the request's headers
   * @param responseHeaders the response's headers
   */
  public void write(
      int status, Duration duration, HttpHeaders requestHeaders, HttpHeaders responseHeaders) {
    if (!log.isInfoEnabled()) {
      return;
    }
    var durationMillis = duration.toNanos() / NANOS_PER_MILLI;
    var fields = new LinkedHashMap<String, Object>();
    fields.putAll(headerFields(requestHeaderFields, requestHeaders));
    fields.putAll(headerFields(responseHeaderFields, responseHeaders));
    // Last, so a header written under the same field name never replaces them.
    fields.put(WebConstants.HTTP_STATUS, status);
    fields.put(WebConstants.DURATION_MS, durationMillis);
    if (tokenPayload) {
      tokenPayloadOf(requestHeaders)
          .ifPresent(payload -> fields.put(WebConstants.JWT_CLAIMS, payload));
    }
    var line =
        log.atInfo()
            .setMessage("{} {} answered {} in {} ms")
            .addArgument(MDC.get(LoggingContextConstants.HTTP_METHOD))
            .addArgument(routeOrPath())
            .addArgument(status)
            .addArgument(durationMillis);
    // A loop, because each key-value pair returns the builder to use next.
    for (var field : fields.entrySet()) {
      line = line.addKeyValue(field.getKey(), field.getValue());
    }
    line.log();
  }

  /** Returns the header fields without the headers that are never logged. */
  private Map<String, String> allowedOnly(
      Map<String, String> fieldsByHeader, Collection<String> deniedHeaders) {
    return fieldsByHeader.entrySet().stream()
        .filter(header -> !deniedHeaders.contains(header.getKey()))
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /** Returns the route the request matched, or its path when it matched none. */
  private String routeOrPath() {
    return Objects.requireNonNullElse(
        MDC.get(LoggingContextConstants.HTTP_ROUTE),
        Objects.requireNonNullElse(MDC.get(LoggingContextConstants.HTTP_PATH), ""));
  }

  /**
   * Returns the value of each named header the request or response has, by its field name. Two
   * headers named with the same field share it.
   */
  private Map<String, String> headerFields(
      Map<String, String> fieldsByHeader, HttpHeaders headers) {
    return fieldsByHeader.entrySet().stream()
        .filter(header -> headers.containsHeader(header.getKey()))
        .collect(
            Collectors.toMap(
                Map.Entry::getValue,
                header ->
                    requestFieldsReader.safeForLogs(
                        String.join(", ", headers.getValuesAsList(header.getKey()))),
                (first, second) -> first + ", " + second));
  }

  /**
   * Returns the listed claims of the caller's signed bearer token, as JSON, if it sent one that has
   * any. An encrypted token, or one whose payload is not a Base64url JSON object, gives none.
   */
  private Optional<String> tokenPayloadOf(HttpHeaders requestHeaders) {
    return Optional.ofNullable(requestHeaders.getFirst(HttpHeaders.AUTHORIZATION))
        .filter(header -> header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length()))
        .map(header -> header.substring(BEARER_PREFIX.length()).strip().split(TOKEN_PART_SEPARATOR))
        .filter(parts -> parts.length == SIGNED_TOKEN_PARTS)
        .flatMap(parts -> decoded(parts[1]))
        .flatMap(this::listedClaims)
        .map(requestFieldsReader::safeForLogs);
  }

  /** Returns the listed claims of a token payload as JSON, or none when it has none of them. */
  private Optional<String> listedClaims(String payload) {
    try {
      return Optional.of(JsonMapper.shared().readTree(payload))
          .filter(ObjectNode.class::isInstance)
          .map(claims -> ((ObjectNode) claims).retain(tokenClaims))
          .filter(claims -> !claims.isEmpty())
          .map(JsonNode::toString);
    } catch (JacksonException notJson) {
      return Optional.empty();
    }
  }

  /** Returns a Base64url part of a token decoded, or nothing when it is not Base64url. */
  private Optional<String> decoded(String tokenPart) {
    try {
      return Optional.of(
          new String(Base64.getUrlDecoder().decode(tokenPart), StandardCharsets.UTF_8));
    } catch (IllegalArgumentException notBase64Url) {
      return Optional.empty();
    }
  }
}
