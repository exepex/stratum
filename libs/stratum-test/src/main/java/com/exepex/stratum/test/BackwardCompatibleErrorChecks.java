package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import lombok.experimental.UtilityClass;

/**
 * Checks what a web starter answers while {@code stratum.web.backward-compatible} is on, the same
 * on both web stacks, against a running service that has no handler for a path.
 */
@UtilityClass
public class BackwardCompatibleErrorChecks {

  /** The header that names the media types the caller accepts. */
  private static final String ACCEPT = "Accept";

  /** The media type of Spring Boot's JSON body. */
  private static final String JSON = "application/json";

  /** The status of an unknown path. */
  private static final int NOT_FOUND = 404;

  /** The status of an invalid body. */
  private static final int BAD_REQUEST = 400;

  /** Sends the requests of the checks that post a body. */
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  /**
   * Checks that a consumer that has not opted in gets Spring Boot's body and media type for an
   * unknown path, with no Stratum code, and no stack trace, exception or message even when the
   * service turned them on.
   *
   * @param port the port the service listens on
   * @param unknownPath a path the service does not have
   * @throws IOException if the request fails
   * @throws InterruptedException if the request is interrupted
   */
  public static void checkBootBody(int port, String unknownPath)
      throws IOException, InterruptedException {
    var response = StarterTestSupport.getFromService(port, unknownPath, ACCEPT, JSON);

    assertThat(response.statusCode()).isEqualTo(NOT_FOUND);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith(JSON));
    assertThat(JsonPath.<String>read(response.body(), "$.error")).isEqualTo("Not Found");
    assertThat(JsonPath.<String>read(response.body(), "$.path")).isEqualTo(unknownPath);
    assertThat(response.body())
        .doesNotContain("\"code\"", "\"trace\"", "\"exception\"", "\"message\"");
  }

  /**
   * Checks that a consumer that has not opted in gets Spring Boot's body for an invalid JSON body,
   * without the binding errors, which would quote the rejected value, even when the service turned
   * them on.
   *
   * @param port the port the service listens on
   * @param path the path that validates the body
   * @param invalidJson a body that fails validation
   * @param rejectedValue a value in the body that must not come back
   * @throws IOException if the request fails
   * @throws InterruptedException if the request is interrupted
   */
  public static void checkInvalidBodyHidesValue(
      int port, String path, String invalidJson, String rejectedValue)
      throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", JSON)
            .header(ACCEPT, JSON)
            .POST(HttpRequest.BodyPublishers.ofString(invalidJson))
            .build();
    var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(BAD_REQUEST);
    assertThat(response.body()).doesNotContain(rejectedValue, "\"errors\"", "\"code\"");
  }

  /**
   * Checks that a consumer that asks for {@code application/problem+json} gets Stratum's body.
   *
   * @param port the port the service listens on
   * @param unknownPath a path the service does not have
   * @param notFoundCode Stratum's code for an unknown path
   * @throws IOException if the request fails
   * @throws InterruptedException if the request is interrupted
   */
  public static void checkOptedInBody(int port, String unknownPath, String notFoundCode)
      throws IOException, InterruptedException {
    StarterTestSupport.checkProblemDetails(
        StarterTestSupport.getFromService(
            port, unknownPath, ACCEPT, JSON + ", application/problem+json"),
        NOT_FOUND,
        notFoundCode);
  }

  /**
   * Checks that a browser gets Stratum's page, titled from Spring Boot's body, with the trace ID as
   * the reference to quote to support.
   *
   * @param port the port the service listens on
   * @param unknownPath a path the service does not have
   * @throws IOException if the request fails
   * @throws InterruptedException if the request is interrupted
   */
  public static void checkBrowserPage(int port, String unknownPath)
      throws IOException, InterruptedException {
    var response = StarterTestSupport.getFromService(port, unknownPath, ACCEPT, "text/html");

    assertThat(response.statusCode()).isEqualTo(NOT_FOUND);
    assertThat(response.body())
        .contains("<title>Not Found</title>")
        .containsPattern("Reference: <code>[0-9a-f]{32}</code>");
  }

  /**
   * Checks that a failure with an error code, which no consumer has seen in another body, gets
   * Stratum's body even for a consumer that has not opted in.
   *
   * @param port the port the service listens on
   * @param path a path that fails with the code
   * @param status the status the service maps the code to
   * @param code the code
   * @throws IOException if the request fails
   * @throws InterruptedException if the request is interrupted
   */
  public static void checkCodedFailureBody(int port, String path, int status, String code)
      throws IOException, InterruptedException {
    StarterTestSupport.checkProblemDetails(
        StarterTestSupport.getFromService(port, path, ACCEPT, JSON), status, code);
  }
}
