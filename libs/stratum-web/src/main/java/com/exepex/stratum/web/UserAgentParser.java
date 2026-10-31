package com.exepex.stratum.web;

import com.exepex.stratum.core.error.LoggingContextConstants;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import nl.basjes.parse.useragent.UserAgent;
import nl.basjes.parse.useragent.UserAgentAnalyzer;
import org.springframework.http.HttpHeaders;

/**
 * Reads the device, operating system and browser of a request from its {@code User-Agent} header
 * and the browser's client hints, with the Yauaa library. Parsed user agents are kept in a bounded
 * cache, because most requests come from a few clients.
 */
public class UserAgentParser {

  /** The logging context field of each Yauaa field that is logged. */
  private static final Map<String, String> FIELD_NAMES_BY_YAUAA_FIELD =
      Map.of(
          UserAgent.DEVICE_CLASS, LoggingContextConstants.DEVICE_CLASS,
          UserAgent.DEVICE_NAME, LoggingContextConstants.DEVICE_NAME,
          UserAgent.OPERATING_SYSTEM_NAME, LoggingContextConstants.OS_NAME,
          UserAgent.OPERATING_SYSTEM_VERSION, LoggingContextConstants.OS_VERSION,
          UserAgent.AGENT_NAME, LoggingContextConstants.BROWSER_NAME,
          UserAgent.AGENT_VERSION_MAJOR, LoggingContextConstants.BROWSER_VERSION);

  /** Parses the user agents; it loads its rules once, at startup. */
  private final UserAgentAnalyzer analyzer;

  /**
   * Makes the parser and loads its rules, which takes a few seconds.
   *
   * @param settings the web settings, whose cache size it uses
   */
  public UserAgentParser(WebProperties settings) {
    this.analyzer =
        UserAgentAnalyzer.newBuilder()
            .hideMatcherLoadStats()
            .dropTests()
            .withCache(settings.userAgent().cacheSize())
            .withFields(FIELD_NAMES_BY_YAUAA_FIELD.keySet())
            .immediateInitialization()
            .build();
  }

  /**
   * Returns the device, operating system and browser fields of a request. A value the parser could
   * not tell is left out.
   *
   * @param headers the request's headers, of which only {@code User-Agent} and the client hints are
   *     read
   * @return the fields, by their names in the logging context
   */
  public Map<String, String> fieldsOf(HttpHeaders headers) {
    var parserInput =
        Stream.concat(
                Stream.of(HttpHeaders.USER_AGENT), analyzer.supportedClientHintHeaders().stream())
            .filter(headers::containsHeader)
            .collect(
                Collectors.toMap(
                    headerName -> headerName,
                    headerName -> Objects.requireNonNullElse(headers.getFirst(headerName), "")));
    if (!parserInput.containsKey(HttpHeaders.USER_AGENT)) {
      return Map.of();
    }
    var parsed = analyzer.parse(parserInput);
    return FIELD_NAMES_BY_YAUAA_FIELD.entrySet().stream()
        .filter(field -> !parsed.get(field.getKey()).isDefaultValue())
        .collect(
            Collectors.toUnmodifiableMap(
                Map.Entry::getValue, field -> parsed.getValue(field.getKey())));
  }

  /**
   * Returns the {@code Accept-CH} header value that asks a browser for every client hint the parser
   * reads, such as the device model and the full operating system version.
   *
   * @return the header names, separated by commas
   */
  public String clientHintsToRequest() {
    return String.join(", ", analyzer.supportedClientHintHeaders());
  }
}
