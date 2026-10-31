package com.exepex.stratum.web;

import com.exepex.stratum.core.error.LoggingContextConstants;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.util.UriUtils;

/**
 * Reads the fields that every log line of a request carries: its method, path and query, the
 * client's IP address, user agent and, when they are set up, the client's device, operating system,
 * browser, country and city. A value the request does not have is left out, never made up. Each
 * value has its control characters replaced, so it cannot forge a log line, and is cut to the
 * configured length.
 */
public class RequestFieldsReader implements AutoCloseable {

  /** The header that names the clients and proxies a request passed through. */
  private static final String FORWARDED_FOR = "X-Forwarded-For";

  /** Any control character, such as a line break. */
  private static final Pattern CONTROL_CHARACTER = Pattern.compile("\\p{Cntrl}");

  /** Finds the client's IP address behind the trusted proxies. */
  private final ClientIpResolver clientIpResolver;

  /** Reads device, operating system and browser, or null when that is off. */
  private final @Nullable UserAgentParser userAgentParser;

  /** Finds the client's country and city, or null when no database is set. */
  private final @Nullable ClientLocationLookup clientLocationLookup;

  /** The most characters a field keeps. */
  private final int maxValueLength;

  /** The {@code Accept-CH} value that asks browsers for client hints, or null when not asked. */
  private final @Nullable String acceptClientHintsValue;

  /**
   * Makes the reader.
   *
   * @param settings the web settings, whose field length it uses
   * @param clientIpResolver finds the client's IP address
   * @param userAgentParser reads device, operating system and browser, or null when that is off
   * @param clientLocationLookup finds the client's country and city, or null when that is off
   */
  public RequestFieldsReader(
      WebProperties settings,
      ClientIpResolver clientIpResolver,
      @Nullable UserAgentParser userAgentParser,
      @Nullable ClientLocationLookup clientLocationLookup) {
    this.clientIpResolver = clientIpResolver;
    this.userAgentParser = userAgentParser;
    this.clientLocationLookup = clientLocationLookup;
    this.maxValueLength = settings.requestFields().maxValueLength();
    this.acceptClientHintsValue =
        userAgentParser != null && settings.userAgent().requestClientHints()
            ? userAgentParser.clientHintsToRequest()
            : null;
  }

  /**
   * Returns the reader the settings call for: user agent parsing only when it is on, and the client
   * location only when a database is set.
   *
   * @param settings the web settings
   * @return the reader
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-WEB-1006} if the client
   *     location database cannot be read, or {@code SX-CORE-1003} if a trusted proxy range is not
   *     valid
   */
  public static RequestFieldsReader from(WebProperties settings) {
    var database = settings.clientLocation().database();
    return new RequestFieldsReader(
        settings,
        new ClientIpResolver(settings),
        settings.userAgent().parse() ? new UserAgentParser(settings) : null,
        database == null ? null : ClientLocationLookup.open(database));
  }

  /**
   * Returns the {@code Accept-CH} response header that asks browsers for the client hints the user
   * agent parser reads, when {@code stratum.web.user-agent.request-client-hints} is on.
   *
   * @return the header value, or nothing when browsers are not asked
   */
  public Optional<String> clientHintsToRequest() {
    return Optional.ofNullable(acceptClientHintsValue);
  }

  /**
   * Closes the client location database, if one is open, when the application stops.
   *
   * @throws IOException if the database cannot be closed
   */
  @Override
  public void close() throws IOException {
    if (clientLocationLookup != null) {
      clientLocationLookup.close();
    }
  }

  /**
   * Returns the fields of a request, by their names in the logging context. The route is not among
   * them, because it is known only once Spring has matched the request to a handler.
   *
   * @param method the request's method
   * @param uri the request's URI, whose path and query values are kept as the client encoded them
   * @param headers the request's headers
   * @param socketAddress the address the request came from, if known
   * @return the fields
   */
  public Map<String, String> fieldsOf(
      HttpMethod method, URI uri, HttpHeaders headers, @Nullable InetAddress socketAddress) {
    var fields = new LinkedHashMap<String, String>();
    fields.put(LoggingContextConstants.HTTP_METHOD, method.name());
    fields.put(LoggingContextConstants.HTTP_PATH, Objects.requireNonNullElse(uri.getRawPath(), ""));
    if (uri.getRawQuery() != null) {
      fields.put(LoggingContextConstants.HTTP_QUERY, withDecodedNames(uri.getRawQuery()));
    }
    var clientIp = clientIpResolver.clientIp(socketAddress, headers.getValuesAsList(FORWARDED_FOR));
    clientIp.ifPresent(
        address -> fields.put(LoggingContextConstants.CLIENT_IP, address.getHostAddress()));
    if (clientLocationLookup != null) {
      clientIp.map(clientLocationLookup::fieldsOf).ifPresent(fields::putAll);
    }
    var userAgent = headers.getFirst(HttpHeaders.USER_AGENT);
    if (userAgent != null) {
      fields.put(LoggingContextConstants.USER_AGENT, userAgent);
    }
    if (userAgentParser != null) {
      fields.putAll(userAgentParser.fieldsOf(headers));
    }
    fields.replaceAll((name, value) -> safeForLogs(value));
    return fields;
  }

  /**
   * Returns the query with each parameter name percent-decoded and each value as the client sent
   * it, so that the logging starter's redaction sees a name such as {@code %74oken} as {@code
   * token} and masks its value.
   */
  private String withDecodedNames(String rawQuery) {
    return Arrays.stream(rawQuery.split("&", -1))
        .map(this::withDecodedName)
        .collect(Collectors.joining("&"));
  }

  /** Returns one query parameter, such as {@code %74oken=x}, with its name decoded. */
  private String withDecodedName(String parameter) {
    var nameEnd = parameter.indexOf('=');
    var name = nameEnd < 0 ? parameter : parameter.substring(0, nameEnd);
    return decodedName(name) + parameter.substring(name.length());
  }

  /**
   * Returns a parameter name percent-decoded. A {@link URI} holds only valid escapes, so decoding
   * cannot fail.
   */
  private String decodedName(String name) {
    return name.indexOf('%') < 0 ? name : UriUtils.decode(name, StandardCharsets.UTF_8);
  }

  /**
   * Returns a value with its control characters replaced by spaces and cut to the configured
   * length, as every request field is stored.
   *
   * @param value the value as the request gave it
   * @return the value to log
   */
  public String safeForLogs(String value) {
    var cut = value.length() > maxValueLength ? value.substring(0, maxValueLength) : value;
    return CONTROL_CHARACTER.matcher(cut).replaceAll(" ");
  }
}
