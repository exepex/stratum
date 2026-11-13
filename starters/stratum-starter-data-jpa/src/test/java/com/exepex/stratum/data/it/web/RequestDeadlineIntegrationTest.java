package com.exepex.stratum.data.it.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import com.exepex.stratum.test.WebTestSupport;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(
    classes = DeadlineOrdersApplication.class,
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "stratum.web.timeout.routes[" + DeadlineOrdersApplication.DEADLINE_ROUTES + "]=1s",
      DataConstants.QUERY_TIMEOUT + "=3s",
      DataConstants.DEADLINE_MARGIN + "=500ms"
    })
@Import(PostgresContainerConfiguration.class)
@DisplayName("Statements and borrows in a web request end by its time left minus the margin")
class RequestDeadlineIntegrationTest {

  /** Carries the request's context to {@code @Async} work, as the observability starter does. */
  private static final String CARRY_CONTEXT = "spring.task.execution.propagate-context=true";

  /** The counter's cause of a statement the pool's limit ended. */
  private static final String QUERY_TIMEOUT_CAUSE = "query-timeout";

  /** The counter's cause of a statement or borrow that ran out of the request's time. */
  private static final String REQUEST_CAUSE = "request";

  /** The web module's code of a request it answered at its own limit. */
  private static final String WEB_TIMEOUT_CODE = "SX-WEB-1007";

  /** Returns the count of {@code stratum.data.timeouts} for one cause. */
  private double timeouts(MeterRegistry meterRegistry, String cause) {
    var counter = meterRegistry.find(DataConstants.TIMEOUTS_METER).tag("cause", cause).counter();
    return counter == null ? 0 : counter.count();
  }

  /**
   * Calls an endpoint and checks it was answered with 503 and SX-DATA-1003, counted with the cause,
   * and not with the web module's own timeout.
   */
  private void checkDataTimeout(int port, MeterRegistry meterRegistry, String path, String cause)
      throws IOException, InterruptedException {
    var before = timeouts(meterRegistry, cause);

    HttpResponse<String> response = WebTestSupport.getFromService(port, path);

    assertThat(response.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    assertThat(JsonPath.<String>read(response.body(), "$.code"))
        .isEqualTo(DataErrorCode.STATEMENT_TIMED_OUT.getCode())
        .isNotEqualTo(WEB_TIMEOUT_CODE);
    assertThat(timeouts(meterRegistry, cause)).isEqualTo(before + 1);
  }

  @Nested
  @TestPropertySource(properties = {"spring.main.web-application-type=servlet", CARRY_CONTEXT})
  @DisplayName("on Spring MVC")
  class Servlet {

    @LocalServerPort private int port;

    @Autowired private MeterRegistry meterRegistry;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("a slow query is cancelled before the web limit, answered 503 SX-DATA-1003")
    void slowQuery() throws IOException, InterruptedException {
      checkDataTimeout(port, meterRegistry, DeadlineOrdersApplication.SLOW_QUERY, REQUEST_CAUSE);
    }

    @Test
    @DisplayName("a borrow with less time left than the margin is answered 503 SX-DATA-1003")
    void lateBorrow() throws IOException, InterruptedException {
      checkDataTimeout(port, meterRegistry, DeadlineOrdersApplication.LATE_BORROW, REQUEST_CAUSE);
    }

    @Test
    @DisplayName(
        "@Async work on an executor that carries the context ends by the request's deadline")
    void carriedAsyncWork() throws IOException, InterruptedException {
      checkDataTimeout(port, meterRegistry, DeadlineOrdersApplication.ASYNC_QUERY, REQUEST_CAUSE);
    }

    @Test
    @DisplayName("a response that started before its limit still runs its statements past it")
    void startedResponse() throws IOException, InterruptedException {
      var response = WebTestSupport.getFromService(port, DeadlineOrdersApplication.LATE_STREAM);

      assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
      assertThat(response.body()).isEqualTo("started answered");
    }

    @Test
    @DisplayName("a statement outside a web request has no request limit")
    void outsideRequest() {
      assertThat(jdbcTemplate.queryForObject("SELECT pg_sleep(1.2)::text", String.class)).isEmpty();
    }
  }

  @Nested
  @TestPropertySource(properties = "spring.main.web-application-type=servlet")
  @DisplayName("on Spring MVC, with an executor that does not carry the context")
  class ServletNotCarried {

    @LocalServerPort private int port;

    @Autowired private MeterRegistry meterRegistry;

    @Test
    @DisplayName("@Async work has no request limit, only the pool's")
    void asyncWorkWithoutDeadline() throws IOException, InterruptedException {
      var before = timeouts(meterRegistry, QUERY_TIMEOUT_CAUSE);

      var response = WebTestSupport.getFromService(port, DeadlineOrdersApplication.ASYNC_QUERY);

      assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo(WEB_TIMEOUT_CODE);
      assertThat(timeouts(meterRegistry, REQUEST_CAUSE)).isZero();
      await().until(() -> timeouts(meterRegistry, QUERY_TIMEOUT_CAUSE) > before);
    }
  }

  @Nested
  @TestPropertySource(properties = {"spring.main.web-application-type=reactive", CARRY_CONTEXT})
  @DisplayName("on Spring WebFlux")
  class Reactive {

    @LocalServerPort private int port;

    @Autowired private MeterRegistry meterRegistry;

    @Test
    @DisplayName("a slow query is cancelled before the web limit, answered 503 SX-DATA-1003")
    void slowQuery() throws IOException, InterruptedException {
      checkDataTimeout(port, meterRegistry, DeadlineOrdersApplication.SLOW_QUERY, REQUEST_CAUSE);
    }

    @Test
    @DisplayName("a borrow with less time left than the margin is answered 503 SX-DATA-1003")
    void lateBorrow() throws IOException, InterruptedException {
      checkDataTimeout(port, meterRegistry, DeadlineOrdersApplication.LATE_BORROW, REQUEST_CAUSE);
    }
  }
}
