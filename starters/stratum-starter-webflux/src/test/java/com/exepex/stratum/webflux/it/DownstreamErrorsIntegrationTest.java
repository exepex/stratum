package com.exepex.stratum.webflux.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.exepex.stratum.test.BackwardCompatibleErrorChecks;
import com.exepex.stratum.test.WebTestSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(
    classes = DownstreamFailuresApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.tracing.sampling.probability=1.0",
      "management.tracing.export.otlp.enabled=false",
      "management.otlp.metrics.export.enabled=false"
    })
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("A reactive request whose call of another service failed uncaught is answered 500")
class DownstreamErrorsIntegrationTest {

  /** Each failing path with the code its caller gets. */
  private static final List<Arguments> FAILURES =
      List.of(
          arguments(DownstreamFailuresApplication.REFUSED_PATH, "SX-HTTPC-0009"),
          arguments(DownstreamFailuresApplication.MISSING_PATH, "SX-HTTPC-0007"),
          arguments(DownstreamFailuresApplication.UNREADABLE_PATH, "SX-HTTPC-0008"));

  @LocalServerPort private int port;

  @Nested
  @DisplayName("in the default mode")
  class DefaultMode {

    @ParameterizedTest
    @FieldSource("com.exepex.stratum.webflux.it.DownstreamErrorsIntegrationTest#FAILURES")
    @DisplayName("gets 500, Stratum's body with the code, and nothing of the other service")
    void answers500WithCode(String path, String code, CapturedOutput output) throws Exception {
      var response = WebTestSupport.getFromService(port, path);

      WebTestSupport.checkProblemDetails(response, HttpStatus.INTERNAL_SERVER_ERROR.value(), code);
      assertThat(response.body())
          .doesNotContain(
              DownstreamFailuresApplication.SECRET, DownstreamFailuresApplication.CLIENT);
      WebTestSupport.checkLoggedOnceAtError(output, code);
    }
  }

  @Nested
  @TestPropertySource(properties = "stratum.backward-compatible=true")
  @DisplayName("in backward-compatible mode")
  class BackwardCompatible {

    @LocalServerPort private int compatiblePort;

    @ParameterizedTest
    @FieldSource("com.exepex.stratum.webflux.it.DownstreamErrorsIntegrationTest#FAILURES")
    @DisplayName("gets 500 and Stratum's body even for a consumer that has not opted in")
    void answers500WithStandardBody(String path, String code) throws Exception {
      BackwardCompatibleErrorChecks.checkCodedFailureBody(
          compatiblePort, path, HttpStatus.INTERNAL_SERVER_ERROR.value(), code);
      assertThat(WebTestSupport.getFromService(compatiblePort, path).body())
          .doesNotContain(
              DownstreamFailuresApplication.SECRET, DownstreamFailuresApplication.CLIENT);
    }
  }
}
