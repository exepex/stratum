package com.exepex.stratum.test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import com.exepex.stratum.core.properties.StratumPropertiesCustomizer;
import com.exepex.stratum.test.fixture.UnswitchedGreeter;
import com.exepex.stratum.test.fixture.autoconfigure.GreetingAutoConfiguration;
import com.exepex.stratum.test.fixture.autoconfigure.GreetingFormatter;
import com.sun.net.httpserver.HttpServer;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("StarterTestSupport gives every starter's tests the same checks and set-up")
class StarterTestSupportTest {

  /** The made-up starter's top package, which the architecture rules are checked against. */
  private static final String FIXTURE_STARTER_PACKAGE = "com.exepex.stratum.test.fixture";

  @Nested
  @DisplayName("withCustomizers")
  class WithCustomizers {

    @Test
    @DisplayName("lists the customizers under their interface while the work runs, then cleans up")
    void listsCustomizersOnlyWhileWorkRuns(@TempDir Path folder) throws IOException {
      var thread = Thread.currentThread();
      var originalClassLoader = thread.getContextClassLoader();

      var listedCustomizers =
          StarterTestSupport.withCustomizers(
              folder,
              GreetingPropertiesCustomizer.class,
              List.of(ShortGreeting.class),
              () -> thread.getContextClassLoader().getResource("META-INF/spring.factories"));

      assertThat(listedCustomizers).isNotNull();
      assertThat(Files.readString(folder.resolve("META-INF/spring.factories")))
          .isEqualTo(
              GreetingPropertiesCustomizer.class.getName() + "=" + ShortGreeting.class.getName());
      assertThat(thread.getContextClassLoader()).isSameAs(originalClassLoader);
    }
  }

  @Nested
  @DisplayName("checkErrorCodes")
  class CheckErrorCodes {

    @Test
    @DisplayName("passes when the codes have the format and the page lists exactly them")
    void passesForDocumentedCodes() throws IOException {
      StarterTestSupport.checkErrorCodes("CORE", CoreErrorCode.values());
    }

    @Test
    @DisplayName("fails when a code is not on the error codes page")
    void failsForUndocumentedCode() {
      assertThatExceptionOfType(AssertionError.class)
          .isThrownBy(() -> StarterTestSupport.checkErrorCodes("CORE", UndocumentedCode.values()));
    }

    @Test
    @DisplayName("fails when a code does not have the module's format")
    void failsForBadlyFormattedCode() {
      assertThatExceptionOfType(AssertionError.class)
          .isThrownBy(() -> StarterTestSupport.checkErrorCodes("LOGGING", CoreErrorCode.values()));
    }
  }

  @Nested
  @DisplayName("the starter architecture rules")
  class StarterArchitecture {

    @Test
    @DisplayName("pass for an autoconfigure package that only declares beans")
    void passForConfigurationClass() {
      var result =
          StarterTestSupport.starterArchitecture(FIXTURE_STARTER_PACKAGE)
              .evaluate(new ClassFileImporter().importClasses(GreetingAutoConfiguration.class));

      assertThat(result.hasViolation()).isFalse();
    }

    @Test
    @DisplayName("fail for logic in the autoconfigure package")
    void failForLogicInAutoconfigurePackage() {
      var result =
          StarterTestSupport.starterArchitecture(FIXTURE_STARTER_PACKAGE)
              .evaluate(new ClassFileImporter().importClasses(GreetingFormatter.class));

      assertThat(result.getFailureReport().getDetails())
          .anyMatch(detail -> detail.contains(GreetingFormatter.class.getName()));
    }

    @Test
    @DisplayName("fail for a class a component scan picks up without the module switch")
    void failForScannableClassWithoutModuleSwitch() {
      var result =
          StarterTestSupport.safeToScan()
              .evaluate(new ClassFileImporter().importClasses(UnswitchedGreeter.class));

      assertThat(result.getFailureReport().getDetails())
          .anyMatch(detail -> detail.contains(UnswitchedGreeter.class.getName()));
    }
  }

  /** A module's customizer interface, as a starter declares one. */
  @FunctionalInterface
  interface GreetingPropertiesCustomizer extends StratumPropertiesCustomizer<String> {}

  /** A service's customizer, which the test lists. */
  static class ShortGreeting implements GreetingPropertiesCustomizer {

    @Override
    public String customize(String greeting) {
      return "Hi";
    }
  }

  /** A code in the right format that the error codes page does not list. */
  @Getter
  @RequiredArgsConstructor
  enum UndocumentedCode implements ErrorCode {
    NOT_ON_THE_PAGE("SX-CORE-9999", Severity.ERROR, "Not on the error codes page");

    private final String code;

    private final Severity severity;

    private final String description;
  }

  @Nested
  @DisplayName("getFromService")
  class GetFromService {

    @Test
    @DisplayName("sends a GET request with the headers and returns the body")
    void sendsRequestWithHeaders() throws IOException, InterruptedException {
      var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(
          "/echo",
          exchange -> {
            var body = exchange.getRequestHeaders().getFirst("X-Order").getBytes(UTF_8);
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
      try {
        var response =
            StarterTestSupport.getFromService(
                server.getAddress().getPort(), "/echo", "X-Order", "42");

        assertThat(response.body()).isEqualTo("42");
      } finally {
        server.stop(0);
      }
    }
  }

  @Nested
  @DisplayName("ipAddress")
  class IpAddress {

    /** An IPv4 address from the documentation range. */
    private static final String IPV4_ADDRESS = StarterTestSupport.ipAddress(198, 51, 100, 20);

    /** An IPv6 address from the documentation range. */
    private static final String IPV6_ADDRESS =
        StarterTestSupport.ipAddress(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1);

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
            + StarterTestSupport.ipAddress(127, 0, 0, 1)
            + "\",\"userAgent\":\"orders-cli/1.0\"}";

    @Test
    @DisplayName("passes a logging context with every request field")
    void passesRequestFields() {
      StarterTestSupport.checkRequestFields(
          bodyOnly(FIELDS_BODY), ROUTE, PATH_AND_QUERY, USER_AGENT);
    }

    @Test
    @DisplayName("fails a logging context with another path or a parsed device field")
    void failsOtherFields() {
      assertThatExceptionOfType(AssertionError.class)
          .isThrownBy(
              () ->
                  StarterTestSupport.checkRequestFields(
                      bodyOnly(FIELDS_BODY), ROUTE, "/orders/7/fields?expand=lines", USER_AGENT));
      assertThatExceptionOfType(AssertionError.class)
          .isThrownBy(
              () ->
                  StarterTestSupport.checkRequestFields(
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
      StarterTestSupport.checkProblemDetails(
          answer("application/problem+json", NOT_FOUND_BODY), NOT_FOUND, "SX-WEB-1003");
    }

    @Test
    @DisplayName("fails an answer with another media type or another code")
    void failsOtherAnswers() {
      assertThatExceptionOfType(AssertionError.class)
          .isThrownBy(
              () ->
                  StarterTestSupport.checkProblemDetails(
                      answer("application/json", NOT_FOUND_BODY), NOT_FOUND, "SX-WEB-1003"));
      assertThatExceptionOfType(AssertionError.class)
          .isThrownBy(
              () ->
                  StarterTestSupport.checkProblemDetails(
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
