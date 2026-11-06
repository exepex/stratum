package com.exepex.stratum.webflux.it;

import com.exepex.stratum.test.ServerRequestTelemetry;
import com.exepex.stratum.test.ServerRequestTelemetry.Recorded;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(
    classes = OrdersApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.tracing.sampling.probability=1.0",
      "management.tracing.export.otlp.enabled=false",
      "management.otlp.metrics.export.enabled=false"
    })
@Import(ServerRequestTelemetry.class)
@DisplayName("A WebFlux request's timer and span carry the error code it failed with")
class RequestErrorCodeIntegrationTest {

  /** What each request records, the same on both web stacks. */
  static final List<Recorded> CASES = ServerRequestTelemetry.ORDERS_CASES;

  @LocalServerPort private int port;

  @Autowired private ServerRequestTelemetry telemetry;

  @ParameterizedTest(name = "{0}")
  @FieldSource("CASES")
  @DisplayName(
      "records the failure, and its code or its cause's, as WebFlux and Spring MVC both do")
  void recordsErrorCode(Recorded expected) throws Exception {
    WebTestSupport.getFromService(port, expected.path());

    telemetry.check(expected);
  }

  @Nested
  @TestPropertySource(properties = "stratum.backward-compatible=true")
  @DisplayName("in backward-compatible mode")
  class BackwardCompatible {

    @LocalServerPort private int compatiblePort;

    @Autowired private ServerRequestTelemetry compatibleTelemetry;

    @Test
    @DisplayName("a StratumException still gets its code, as metrics and spans are internal")
    void recordsErrorCode() throws Exception {
      WebTestSupport.getFromService(compatiblePort, ServerRequestTelemetry.DUPLICATE_ORDER.path());

      compatibleTelemetry.check(ServerRequestTelemetry.DUPLICATE_ORDER);
    }
  }
}
