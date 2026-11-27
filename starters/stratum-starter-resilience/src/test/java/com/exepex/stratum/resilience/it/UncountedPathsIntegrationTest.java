package com.exepex.stratum.resilience.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.resilience.ResilienceConstants;
import com.exepex.stratum.resilience.testsupport.HeldRequests;
import com.exepex.stratum.resilience.testsupport.RouteRequests;
import com.exepex.stratum.test.WebTestSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(
    classes = LimitedApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      ResilienceConstants.INCOMING_ROUTES + "[/**].max-concurrent=1",
      "management.endpoints.web.exposure.include=health,info,orders-admin"
    })
@DisplayName("Actuator and probe paths are never counted, even under a route of /**")
class UncountedPathsIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private HeldRequests held;

  /** Returns the status a GET to the path gets while the /** route is full. */
  private int statusWhileFull(String path) throws Exception {
    return RouteRequests.whileFull(
        port,
        HeldController.HELD_PATH,
        held,
        () -> WebTestSupport.getFromService(port, path).statusCode());
  }

  @Nested
  @DisplayName("with the default base path /actuator")
  class DefaultBasePath {

    /** The discovery page and the exposed endpoints, a custom one included. */
    private static final List<String> ACTUATOR_PATHS =
        List.of("/actuator", "/actuator/health", "/actuator/info", "/actuator/orders-admin");

    @ParameterizedTest(name = "{0}")
    @FieldSource("ACTUATOR_PATHS")
    @DisplayName("each actuator path is answered while the route is full")
    void actuatorPathsAnswered(String path) throws Exception {
      assertThat(statusWhileFull(path)).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("an unknown actuator path gets 404: its error dispatch is not counted either")
    void errorDispatchNotCounted() throws Exception {
      assertThat(statusWhileFull("/actuator/missing")).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    @DisplayName("a service route is refused while the route is full")
    void serviceRouteRefused() throws Exception {
      assertThat(statusWhileFull("/reports/1")).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    }
  }

  @Nested
  @DisplayName("with base path /manage")
  @TestPropertySource(properties = "management.endpoints.web.base-path=/manage")
  class ManageBasePath {

    @Test
    @DisplayName("/manage and /manage/health are answered, /managex is refused")
    void basePathAndBelow() throws Exception {
      assertThat(statusWhileFull("/manage")).isEqualTo(HttpStatus.OK.value());
      assertThat(statusWhileFull("/manage/health")).isEqualTo(HttpStatus.OK.value());
      assertThat(statusWhileFull("/managex")).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    }
  }

  @Nested
  @DisplayName("with the root base path")
  @TestPropertySource(properties = "management.endpoints.web.base-path=/")
  class RootBasePath {

    @Test
    @DisplayName("/health and /info are answered, a service route is still refused")
    void endpointsOnly() throws Exception {
      assertThat(statusWhileFull("/health")).isEqualTo(HttpStatus.OK.value());
      assertThat(statusWhileFull("/info")).isEqualTo(HttpStatus.OK.value());
      assertThat(statusWhileFull("/reports/1")).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    }
  }

  @Nested
  @DisplayName("with the probes and a group on the main port")
  @TestPropertySource(
      properties = {
        "management.endpoint.health.probes.add-additional-paths=true",
        "management.endpoint.health.group.edge.include=ping",
        "management.endpoint.health.group.edge.additional-path=server:/healthz"
      })
  class ProbesOnMainPort {

    /** The probe paths Spring Boot serves on the main port. */
    private static final List<String> PROBE_PATHS = List.of("/livez", "/readyz", "/healthz");

    @ParameterizedTest(name = "{0}")
    @FieldSource("PROBE_PATHS")
    @DisplayName("each probe path is answered while the route is full")
    void probesAnswered(String path) throws Exception {
      assertThat(statusWhileFull(path)).isEqualTo(HttpStatus.OK.value());
    }
  }

  @Nested
  @DisplayName("with actuator on its own port")
  @TestPropertySource(
      properties = {
        "management.server.port=0",
        "management.endpoint.health.group.edge.include=ping",
        "management.endpoint.health.group.edge.additional-path=management:/mgmt-ping"
      })
  class SeparateManagementPort {

    @Test
    @DisplayName("a service route under /actuator and a management group path are counted")
    void mainPortPathsCounted() throws Exception {
      assertThat(statusWhileFull("/actuator/health"))
          .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
      assertThat(statusWhileFull("/mgmt-ping")).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    }
  }
}
