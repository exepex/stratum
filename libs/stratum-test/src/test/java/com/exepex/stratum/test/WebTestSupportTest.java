package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("WebTestSupport gives the web modules' tests the same values and checks")
class WebTestSupportTest {

  @Nested
  @DisplayName("ipAddress")
  class IpAddress {

    /** An IPv4 address from the documentation range. */
    private static final String IPV4_ADDRESS = WebTestSupport.ipAddress(198, 51, 100, 20);

    /** An IPv6 address from the documentation range. */
    private static final String IPV6_ADDRESS =
        WebTestSupport.ipAddress(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1);

    @Test
    @DisplayName("writes four numbers as an IPv4 address and eight as an IPv6 address")
    void writesIpv4AndIpv6() {
      assertThat(IPV4_ADDRESS).isEqualTo(String.join(".", "198", "51", "100", "20"));
      assertThat(IPV6_ADDRESS)
          .isEqualTo(String.join(":", "2001", "db8", "0", "0", "0", "0", "0", "1"));
    }
  }

  @Nested
  @DisplayName("checkRequestFields")
  class CheckRequestFields {

    /** The route of the test endpoint. */
    private static final String ROUTE = "/orders/{orderId}/fields";

    /** The path and query the test request sends. */
    private static final String PATH_AND_QUERY = "/orders/42/fields?expand=lines";

    /** The user agent the test request sends. */
    private static final String USER_AGENT = "orders-cli/1.0";

    /** The logging context of the test request, as the endpoint returns it. */
    private static final String FIELDS_BODY =
        "{\"httpMethod\":\"GET\",\"httpRoute\":\"/orders/{orderId}/fields\","
            + "\"httpPath\":\"/orders/42/fields\",\"httpQuery\":\"expand=lines\","
            + "\"clientIp\":\""
            + WebTestSupport.ipAddress(127, 0, 0, 1)
            + "\",\"userAgent\":\"orders-cli/1.0\"}";

    @Test
    @DisplayName("passes a logging context with every request field")
    void passesRequestFields() {
      WebTestSupport.checkRequestFields(bodyOnly(FIELDS_BODY), ROUTE, PATH_AND_QUERY, USER_AGENT);
    }

    @Test
    @DisplayName("fails a logging context with another path or a parsed device field")
    void failsOtherFields() {
      assertThatExceptionOfType(AssertionError.class)
          .isThrownBy(
              () ->
                  WebTestSupport.checkRequestFields(
                      bodyOnly(FIELDS_BODY), ROUTE, "/orders/7/fields?expand=lines", USER_AGENT));
      assertThatExceptionOfType(AssertionError.class)
          .isThrownBy(
              () ->
                  WebTestSupport.checkRequestFields(
                      bodyOnly(FIELDS_BODY.replaceFirst("\\}$", ",\"deviceClass\":\"Phone\"}")),
                      ROUTE,
                      PATH_AND_QUERY,
                      USER_AGENT));
    }

    /** Returns a response with only the body. */
    private HttpResponse<String> bodyOnly(String body) {
      HttpResponse<String> response = mock();
      when(response.body()).thenReturn(body);
      return response;
    }
  }

  @Nested
  @DisplayName("checkProblemDetails")
  class CheckProblemDetails {

    /** The body of a web starter's answer for an unknown path. */
    private static final String NOT_FOUND_BODY =
        "{\"status\":404,\"code\":\"SX-WEB-1003\","
            + "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\"}";

    /** The status of an unknown path. */
    private static final int NOT_FOUND = 404;

    @Test
    @DisplayName("passes an answer with the status, media type, code and a trace ID")
    void passesProblemDetails() {
      WebTestSupport.checkProblemDetails(
          answer("application/problem+json", NOT_FOUND_BODY), NOT_FOUND, "SX-WEB-1003");
    }

    @Test
    @DisplayName("fails an answer with another media type or another code")
    void failsOtherAnswers() {
      assertThatExceptionOfType(AssertionError.class)
          .isThrownBy(
              () ->
                  WebTestSupport.checkProblemDetails(
                      answer("application/json", NOT_FOUND_BODY), NOT_FOUND, "SX-WEB-1003"));
      assertThatExceptionOfType(AssertionError.class)
          .isThrownBy(
              () ->
                  WebTestSupport.checkProblemDetails(
                      answer("application/problem+json", NOT_FOUND_BODY),
                      NOT_FOUND,
                      "SX-WEB-1005"));
    }

    /** Returns an answer with status 404, the media type and the body. */
    private HttpResponse<String> answer(String mediaType, String body) {
      HttpResponse<String> response = mock();
      when(response.statusCode()).thenReturn(NOT_FOUND);
      when(response.headers())
          .thenReturn(
              HttpHeaders.of(Map.of("Content-Type", List.of(mediaType)), (name, value) -> true));
      when(response.body()).thenReturn(body);
      return response;
    }
  }
}
