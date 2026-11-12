package com.exepex.stratum.data.it.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.test.BackwardCompatibleErrorChecks;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import com.exepex.stratum.test.WebTestSupport;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(
    classes = SlowOrdersApplication.class,
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = DataConstants.QUERY_TIMEOUT + "=300ms")
@Import(PostgresContainerConfiguration.class)
@DisplayName("A request whose statement Stratum cancelled is answered on both web stacks")
class StatementTimeoutAnswerIntegrationTest {

  /** The code of a cancelled statement. */
  private static final String CODE = "SX-DATA-1003";

  /** The status of a request that failed with it. */
  private static final int STATUS = HttpStatus.INTERNAL_SERVER_ERROR.value();

  /**
   * Checks Stratum's body: the code and only its fixed description, with no limit, data source or
   * SQL.
   */
  private static void checkStratumBody(int port) throws IOException, InterruptedException {
    var response = WebTestSupport.getFromService(port, SlowOrdersApplication.SLOW_ORDERS);
    assertThat(response.statusCode()).isEqualTo(STATUS);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValue(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo(CODE);
    assertThat(JsonPath.<String>read(response.body(), "$.detail"))
        .isEqualTo(DataErrorCode.STATEMENT_TIMED_OUT.getDescription());
    assertThat(response.body())
        .doesNotContain("dataSource")
        .doesNotContain("300 ms")
        .doesNotContain("pg_sleep");
  }

  /** Checks Spring Boot's own body, with no code, as the service answered before Stratum. */
  private static void checkBootBody(int port) throws IOException, InterruptedException {
    BackwardCompatibleErrorChecks.checkBootBodyStatus(
        port,
        SlowOrdersApplication.SLOW_ORDERS,
        STATUS,
        HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
    assertThat(WebTestSupport.getFromService(port, SlowOrdersApplication.SLOW_ORDERS).body())
        .doesNotContain(CODE);
  }

  @Nested
  @TestPropertySource(properties = "spring.main.web-application-type=servlet")
  @DisplayName("on Spring MVC, outside backward-compatible mode")
  class ServletStandard {

    @LocalServerPort private int port;

    @Test
    @DisplayName("with status 500, SX-DATA-1003 and no detail")
    void answersWithCode() throws IOException, InterruptedException {
      checkStratumBody(port);
    }
  }

  @Nested
  @TestPropertySource(
      properties = {"spring.main.web-application-type=servlet", "stratum.backward-compatible=true"})
  @DisplayName("on Spring MVC, in backward-compatible mode")
  class ServletCompatible {

    @LocalServerPort private int port;

    @Test
    @DisplayName("with Spring Boot's own body, as before")
    void answersAsBefore() throws IOException, InterruptedException {
      checkBootBody(port);
    }
  }

  @Nested
  @TestPropertySource(properties = "spring.main.web-application-type=reactive")
  @DisplayName("on Spring WebFlux, outside backward-compatible mode")
  class ReactiveStandard {

    @LocalServerPort private int port;

    @Test
    @DisplayName("with status 500, SX-DATA-1003 and no detail")
    void answersWithCode() throws IOException, InterruptedException {
      checkStratumBody(port);
    }
  }

  @Nested
  @TestPropertySource(
      properties = {
        "spring.main.web-application-type=reactive",
        "stratum.backward-compatible=true"
      })
  @DisplayName("on Spring WebFlux, in backward-compatible mode")
  class ReactiveCompatible {

    @LocalServerPort private int port;

    @Test
    @DisplayName("with Spring Boot's own body, as before")
    void answersAsBefore() throws IOException, InterruptedException {
      checkBootBody(port);
    }
  }
}
