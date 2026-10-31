package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.testsupport.WebTestData;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

@DisplayName("RequestFieldsReader reads each request's log fields, safely and without guessing")
class RequestFieldsReaderTest {

  /** The address of a client that calls the service directly. */
  private static final String CLIENT_ADDRESS = StarterTestSupport.ipAddress(198, 51, 100, 20);

  /** The client, as the server sees it. */
  private static final InetAddress CLIENT = InetAddress.ofLiteral(CLIENT_ADDRESS);

  /** A request URI with a path and a query. */
  private static final URI ORDER_URI = URI.create("http://orders.example/orders/42?expand=lines");

  /** Returns a reader with the default settings and the given optional parts. */
  private static RequestFieldsReader reader(
      WebProperties settings, UserAgentParser parser, ClientLocationLookup location) {
    return new RequestFieldsReader(settings, new ClientIpResolver(settings), parser, location);
  }

  @Nested
  @DisplayName("with the default settings")
  class DefaultSettings {

    /** The reader a service gets when it sets nothing. */
    private final RequestFieldsReader reader =
        RequestFieldsReader.from(WebTestData.defaults().build());

    @Test
    @DisplayName("reads method, path, query, client IP and the raw user agent")
    void readsFields() {
      var headers = new HttpHeaders();
      headers.set(HttpHeaders.USER_AGENT, "orders-cli/1.0");

      assertThat(reader.fieldsOf(HttpMethod.POST, ORDER_URI, headers, CLIENT))
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(
                  LoggingContextConstants.HTTP_METHOD, "POST",
                  LoggingContextConstants.HTTP_PATH, "/orders/42",
                  LoggingContextConstants.HTTP_QUERY, "expand=lines",
                  LoggingContextConstants.CLIENT_IP, CLIENT_ADDRESS,
                  LoggingContextConstants.USER_AGENT, "orders-cli/1.0"));
    }

    @Test
    @DisplayName("leaves out what the request does not have, instead of making it up")
    void leavesOutMissingValues() {
      var fields = reader.fieldsOf(HttpMethod.GET, URI.create("/orders"), new HttpHeaders(), null);

      assertThat(fields)
          .containsOnlyKeys(LoggingContextConstants.HTTP_METHOD, LoggingContextConstants.HTTP_PATH);
    }

    @Test
    @DisplayName("replaces control characters, so a value cannot forge a log line")
    void replacesControlCharacters() {
      var headers = new HttpHeaders();
      headers.set(HttpHeaders.USER_AGENT, "evil\tagent");

      assertThat(reader.fieldsOf(HttpMethod.GET, ORDER_URI, headers, CLIENT))
          .containsEntry(LoggingContextConstants.USER_AGENT, "evil agent");
    }

    @Test
    @DisplayName("asks browsers for no client hints")
    void noClientHints() {
      assertThat(reader.clientHintsToRequest()).isEmpty();
    }
  }

  @Nested
  @DisplayName("with the service's own settings and lookups")
  class OwnSettings {

    /** The shortest field length a service can set. */
    private static final int SHORTEST_LENGTH = 16;

    @Test
    @DisplayName("cuts each value to max-value-length")
    void cutsLongValues() {
      var settings =
          WebTestData.defaults()
              .requestFields(
                  WebProperties.RequestFieldsSettings.builder()
                      .maxValueLength(SHORTEST_LENGTH)
                      .build())
              .build();
      var headers = new HttpHeaders();
      headers.set(HttpHeaders.USER_AGENT, "a".repeat(SHORTEST_LENGTH * 2));

      var fields =
          reader(settings, null, null).fieldsOf(HttpMethod.GET, ORDER_URI, headers, CLIENT);

      assertThat(fields.get(LoggingContextConstants.USER_AGENT)).hasSize(SHORTEST_LENGTH);
    }

    @Test
    @DisplayName("adds the parsed user agent and the client's location when they are on")
    void addsParsedAgentAndLocation() throws IOException {
      var parser = mock(UserAgentParser.class);
      when(parser.fieldsOf(any())).thenReturn(Map.of(LoggingContextConstants.OS_NAME, "Android"));
      when(parser.clientHintsToRequest()).thenReturn("Sec-CH-UA-Model");
      var location = mock(ClientLocationLookup.class);
      when(location.fieldsOf(CLIENT))
          .thenReturn(Map.of(LoggingContextConstants.CLIENT_COUNTRY, "NL"));
      var settings =
          WebTestData.defaults()
              .userAgent(
                  WebProperties.UserAgentSettings.builder()
                      .parse(true)
                      .requestClientHints(true)
                      .build())
              .build();

      try (var reader = reader(settings, parser, location)) {
        assertThat(reader.fieldsOf(HttpMethod.GET, ORDER_URI, new HttpHeaders(), CLIENT))
            .containsEntry(LoggingContextConstants.OS_NAME, "Android")
            .containsEntry(LoggingContextConstants.CLIENT_COUNTRY, "NL");
        assertThat(reader.clientHintsToRequest()).hasValue("Sec-CH-UA-Model");
      }
    }
  }
}
